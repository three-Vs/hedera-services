package com.hedera.services.state.merkle.v3.collections;

import com.hedera.services.state.merkle.v3.collections.OffHeapLongList;
import com.hedera.services.state.merkle.v3.files.DataFileCollection;
import com.hedera.services.state.merkle.v3.files.DataFileReader;
import com.hedera.services.state.merkle.v3.files.DataFileReaderAsynchronous;
import com.swirlds.virtualmap.VirtualKey;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectLongHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * This is a hash map implementation where the bucket index is in RAM and the buckets are on disk. It maps a VKey to a
 * long value. This allows very large maps with minimal RAM usage and the best performance profile as by using an in
 * memory index we avoid the need for random disk writes. Random disk writes are horrible performance wise in our
 * testing.
 *
 * This implementation depends on good hashCode() implementation on the keys, if there are too many hash collisions the
 * performance can get bad.
 *
 * IMPORTANT: This implementation assumes a single writing thread. There can be multiple readers while writing is happening.
 */
public class HalfDiskHashMap<K extends VirtualKey> implements AutoCloseable {
    /**
     * Nominal value for a bucket location that doesn't exist. It is zero, so we don't need to fill empty index memory
     * with some other value.
     */
    private static final long NON_EXISTENT_BUCKET = 0;
    /** System page size, for now hard coded as it is nearly always 4k on linux */
    private static final int DISK_PAGE_SIZE_BYTES = 4096;
    /** The amount of data used for a header in each bucket */
    private static final int BUCKET_HEADER_SIZE = Integer.BYTES + Integer.BYTES + Long.BYTES;
    /** how full should all available bins be if we are at the specified map size */
    private static final double LOADING_FACTOR = 0.5;
    private final OffHeapLongList bucketIndexToBucketLocation;
    private final DataFileCollection fileCollection;
    private final int entrySize;
    private final int valueOffsetInEntry;
    private final int minimumBuckets;
    private final int numOfBuckets;
    private final int entriesPerBucket;
    private final long mapSize;
    private final String storeName;
    /** Temporary bucket buffers. */
    private final ThreadLocal<Bucket<K>> bucket;
    private IntObjectHashMap<ObjectLongHashMap<K>> oneTransactionsData = null;

    /**
     * Construct a new HalfDiskHashMap
     *
     * @param mapSize The maximum map number of entries. This should be more than big enough to avoid too many key collisions.
     * @param keySize The key size in bytes when serialized to a ByteBuffer
     * @param keyConstructor The constructor to use for creating new de-serialized keys
     * @param storeDir The directory to use for storing data files.
     * @param storeName The name for the data store, this allows more than one data store in a single directory.
     * @throws IOException If there was a problem creating or opening a set of data files.
     */
    public HalfDiskHashMap(long mapSize, int keySize, Supplier<K> keyConstructor, Path storeDir,
                           String storeName) throws IOException {
        this.mapSize = mapSize;
        this.storeName = storeName;
        // create store dir
        Files.createDirectories(storeDir);
        // create bucket index
        bucketIndexToBucketLocation = new OffHeapLongList();
        // calculate number of entries we can store in a disk page
        valueOffsetInEntry = Integer.BYTES + Integer.BYTES + keySize;
        entrySize = valueOffsetInEntry + Long.BYTES; // key hash code, key serialization version, serialized key, long value
        entriesPerBucket = ((DISK_PAGE_SIZE_BYTES-BUCKET_HEADER_SIZE) / entrySize);
        minimumBuckets = (int)Math.ceil(((double)mapSize/LOADING_FACTOR)/entriesPerBucket);
        numOfBuckets = Integer.highestOneBit(minimumBuckets)*2; // nearest greater power of two
        bucket = ThreadLocal.withInitial(() -> new Bucket<>(entrySize, valueOffsetInEntry, entriesPerBucket, keyConstructor));
        // create file collection
        fileCollection = new DataFileCollection(storeDir,storeName,DISK_PAGE_SIZE_BYTES,
                (key, dataLocation, dataValue) -> bucketIndexToBucketLocation.put(key,dataLocation));
    }

    /**
     * Merge all read only files
     *
     * @param maxSizeMb all files returned are smaller than this number of MB
     * @throws IOException if there was a problem merging
     */
    public void mergeAll(int maxSizeMb) throws IOException {
        List<DataFileReader> filesToMerge = fileCollection.getAllFullyWrittenFiles(maxSizeMb);
        final int size = filesToMerge == null ? 0 : filesToMerge.size();
        if (size > 1) {
            System.out.println("Merging " + size+" files in collection "+storeName);
            fileCollection.mergeFile(
                    moves -> {
                        // update index with all moved data
                        moves.forEachKeyValue((key, move) -> bucketIndexToBucketLocation.putIfEqual(key, move[0], move[1]));
                    }, filesToMerge);
        }
    }

    /**
     * Close this HalfDiskHashMap's data files. Once closed this HalfDiskHashMap can not be reused. You should make
     * sure you call close before system exit otherwise any files being written might not be in a good state.
     *
     * @throws IOException If there was a problem closing the data files.
     */
    @Override
    public void close() throws IOException {
        fileCollection.close();
    }

    // =================================================================================================================
    // Writing API - Single thead safe

    /**
     * Start a writing session to the map. Each new writing session results in a new data file on disk, so you should
     * ideally batch up map writes.
     */
    public void startWriting() {
        oneTransactionsData = new IntObjectHashMap<>();
    }

    /**
     * Put a key/value during the current writing session. The value will not be retrievable until it is committed in
     * the endWriting() call.
     *
     * @param key the key to store the value for
     * @param value the value to store for given key
     */
    public void put(K key, long value) {
        if (key == null) throw new IllegalArgumentException("Can not put a null key");
        if (oneTransactionsData == null) throw new IllegalStateException("Trying to write to a HalfDiskHashMap when you have not called startWriting().");
        // store key and value in transaction cache
        int bucketIndex = computeBucketIndex(hash(key));
        ObjectLongHashMap<K> bucketMap = oneTransactionsData.getIfAbsentPut(bucketIndex, ObjectLongHashMap::new);
        bucketMap.put(key,value);
    }

    /**
     * End current writing session, committing all puts to data store.
     *
     * @throws IOException If there was a problem committing data to store
     */
    public void endWriting() throws IOException {
        // iterate over transaction cache and save it all to file
        if (oneTransactionsData != null && !oneTransactionsData.isEmpty()) {
            //  write to files
            fileCollection.startWriting();
            // for each changed bucket
            try {
                oneTransactionsData.forEachKeyValue((bucketIndex, bucketMap) -> {
                    try {
                        long currentBucketLocation = bucketIndexToBucketLocation.get(bucketIndex, NON_EXISTENT_BUCKET);
                        Bucket<K> bucket = this.bucket.get().clear();
                        if (currentBucketLocation == NON_EXISTENT_BUCKET) {
                            // create a new bucket
                            bucket.setBucketIndex(bucketIndex);
                        } else {
                            // load bucket
                            fileCollection.readData(currentBucketLocation, bucket.bucketBuffer, DataFileReaderAsynchronous.DataToRead.VALUE);
                        }
                        // for each changed key in bucket, update bucket
                        bucketMap.forEachKeyValue((k,v) -> bucket.putValue(hash(k),k,v));
                        // save bucket
                        long bucketLocation;
                        bucket.bucketBuffer.rewind();
                        bucketLocation = fileCollection.storeData(bucketIndex, bucket.bucketBuffer);
                        // update bucketIndexToBucketLocation
                        bucketIndexToBucketLocation.put(bucketIndex, bucketLocation);
                    } catch (IOException e) { // wrap IOExceptions
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) { // unwrap IOExceptions
                if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                throw e;
            }
            // close files session
            fileCollection.endWriting(0,numOfBuckets);
        }
        // clear put cache
        oneTransactionsData = null;
    }

    // =================================================================================================================
    // Reading API - Multi thead safe

    /**
     * Get a value from this map
     *
     * @param key The key to get value for
     * @param notFoundValue the value to return if the key was not found
     * @return the value retrieved from the map or {notFoundValue} if no value was stored for the given key
     * @throws IOException If there was a problem reading from the map
     */
    public long get(K key, long notFoundValue) throws IOException {
        if (key == null) throw new IllegalArgumentException("Can not get a null key");
        int keyHash = hash(key);
        int bucketIndex = computeBucketIndex(keyHash);
        long currentBucketLocation = bucketIndexToBucketLocation.get(bucketIndex, NON_EXISTENT_BUCKET);
        if (currentBucketLocation != NON_EXISTENT_BUCKET) {
            Bucket<K> bucket = this.bucket.get().clear();
            // load bucket
            fileCollection.readData(currentBucketLocation,bucket.bucketBuffer, DataFileReaderAsynchronous.DataToRead.VALUE);
            // get
            return bucket.findValue(keyHash,key,notFoundValue);
        }
        return notFoundValue;
    }

    // =================================================================================================================
    // Debugging Print API

    /** Debug dump stats for this map */
    public void printStats() {
        System.out.println("HalfDiskHashMap Stats {");
        System.out.println("    mapSize = " + mapSize);
        System.out.println("    minimumBuckets = " + minimumBuckets);
        System.out.println("    numOfBuckets = " + numOfBuckets);
        System.out.println("    entriesPerBucket = " + entriesPerBucket);
        System.out.println("    entrySize = " + entrySize);
        System.out.println("    valueOffsetInEntry = " + valueOffsetInEntry);
        System.out.println("}");
    }

    /** Useful debug method to print the current state of the transaction cache */
    @SuppressWarnings("unused")
    public void debugDumpTransactionCache() {
        System.out.println("=========== TRANSACTION CACHE ==========================");
        for (int bucketIndex = 0; bucketIndex < numOfBuckets; bucketIndex++) {
            ObjectLongHashMap<K> bucketMap = oneTransactionsData.get(bucketIndex);
            if (bucketMap != null) {
                String tooBig = (bucketMap.size() > entriesPerBucket) ? " TOO MANY! > "+entriesPerBucket : "";
                System.out.println("bucketIndex ["+bucketIndex+"] , count="+bucketMap.size()+tooBig);
//            bucketMap.forEachKeyValue((k, l) -> {
//                System.out.println("        keyHash ["+k.hashCode()+"] bucket ["+(k.hashCode()% numOfBuckets)+"]  key ["+k+"] value ["+l+"]");
//            });
            } else {
                System.out.println("bucketIndex ["+bucketIndex+"] , EMPTY!");
            }
        }
        System.out.println("========================================================");
    }

    // =================================================================================================================
    // Private API

    /**
     * Computes which bucket a key with the given hash falls. Depends on the fact the numOfBuckets is a power of two.
     * Based on same calculation that is used in java HashMap.
     *
     * @param keyHash the int hash for key
     * @return the index of the bucket that key falls in
     */
    private int computeBucketIndex(int keyHash) {
        return (numOfBuckets-1) & keyHash;
    }

    /**
     * From Java HashMap
     *
     * Computes key.hashCode() and spreads (XORs) higher bits of hash
     * to lower.  Because the table uses power-of-two masking, sets of
     * hashes that vary only in bits above the current mask will
     * always collide. (Among known examples are sets of Float keys
     * holding consecutive whole numbers in small tables.)  So we
     * apply a transform that spreads the impact of higher bits
     * downward. There is a tradeoff between speed, utility, and
     * quality of bit-spreading. Because many common sets of hashes
     * are already reasonably distributed (so don't benefit from
     * spreading), and because we use trees to handle large sets of
     * collisions in bins, we just XOR some shifted bits in the
     * cheapest possible way to reduce systematic lossage, as well as
     * to incorporate impact of the highest bits that would otherwise
     * never be used in index calculations because of table bounds.
     */
    private static int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    /**
     * Class for accessing the data in a bucket. This is designed to be used from a single thread.
     *
     * Each bucket has a header containing:
     *  - Bucket index in map hash index
     *  - Count of keys stored
     *  - Long pointer to next bucket if this one is full. TODO implement this
     */
    private static final class Bucket<K extends VirtualKey> {
        private static final int BUCKET_ENTRY_COUNT_OFFSET = Integer.BYTES;
        private static final int NEXT_BUCKET_OFFSET = BUCKET_ENTRY_COUNT_OFFSET + Integer.BYTES;
        private final ByteBuffer bucketBuffer = ByteBuffer.allocateDirect(DISK_PAGE_SIZE_BYTES);
        private final int entrySize;
        private final int valueOffsetInEntry;
        private final int entriesPerBucket;
        private final Supplier<K> keyConstructor;

        private Bucket(int entrySize, int valueOffsetInEntry, int entriesPerBucket, Supplier<K> keyConstructor) {
            this.entrySize = entrySize;
            this.valueOffsetInEntry = valueOffsetInEntry;
            this.entriesPerBucket = entriesPerBucket;
            this.keyConstructor = keyConstructor;
        }

        /** Reset for next use */
        public Bucket<K> clear() {
            setBucketIndex(-1);
            setBucketEntryCount(0);
            bucketBuffer.clear();
            return this;
        }

        public int getBucketIndex() {
            return bucketBuffer.getInt(0);
        }

        public void setBucketIndex(int bucketIndex) {
            this.bucketBuffer.putInt(0,bucketIndex);
        }

        public int getBucketEntryCount() {
            return bucketBuffer.getInt(BUCKET_ENTRY_COUNT_OFFSET);
        }

        public void setBucketEntryCount(int entryCount) {
            this.bucketBuffer.putInt(BUCKET_ENTRY_COUNT_OFFSET,entryCount);
        }

        public long getNextBucketLocation() {
            return bucketBuffer.getLong(NEXT_BUCKET_OFFSET);
        }

        @SuppressWarnings("unused") // TODO still needs to be implemented over flow into a second bucket
        public void setNextBucketLocation(long bucketLocation) {
            this.bucketBuffer.putLong(NEXT_BUCKET_OFFSET,bucketLocation);
        }

        public long findValue(int keyHash, K key, long notFoundValue) throws IOException {
            int entryCount =  getBucketEntryCount();
            for (int i = 0; i < entryCount; i++) {
                int entryOffset = BUCKET_HEADER_SIZE + (i*entrySize);
                int readHash = bucketBuffer.getInt(entryOffset);
//                System.out.println("            readHash = " + readHash+" keyHash = "+keyHash+" == "+(readHash == keyHash));
                if (readHash == keyHash) {
                    // now check the full key
                    bucketBuffer.position(entryOffset+Integer.BYTES);
                    int keySerializationVersion = bucketBuffer.getInt();
                    if (key.equals(bucketBuffer,keySerializationVersion)) {
                        // yay we found it
                        return getValue(entryOffset);
                    }
                }
            }
            return notFoundValue;
        }

        private K getKey(int entryOffset) throws IOException {
            bucketBuffer.position(entryOffset+Integer.BYTES);
            int keySerializationVersion = bucketBuffer.getInt();
            K key = keyConstructor.get();
            key.deserialize(bucketBuffer,keySerializationVersion);
            return key;
        }

        private long getValue(int entryOffset) {
            return bucketBuffer.getLong(entryOffset+valueOffsetInEntry);
        }

        /**
         * Put a key/value entry into this bucket.
         *
         * @param key the entry key
         * @param value the entry value
         */
        public void putValue(int keyHash, K key, long value) {
            try {
                final int entryCount =  getBucketEntryCount();
                for (int i = 0; i < entryCount; i++) {
                    int entryOffset = BUCKET_HEADER_SIZE + (i*entrySize);
                    int readHash = bucketBuffer.getInt(entryOffset);
                    if (readHash == keyHash) {
                        // now check the full key
                        bucketBuffer.position(entryOffset+Integer.BYTES);
                        int keySerializationVersion = bucketBuffer.getInt();
                        if (key.equals(bucketBuffer,keySerializationVersion)) {
                            // yay we found it, so update value
                            bucketBuffer.putLong(entryOffset+valueOffsetInEntry, value);
                            return;
                        }
                    }
                }
                // so there are no entries that match the key
                if (entryCount < entriesPerBucket) {
                        // add a new entry
                        int entryOffset = BUCKET_HEADER_SIZE + (entryCount*entrySize);
                        // write entry
                        bucketBuffer.position(entryOffset);
                        bucketBuffer.putInt(keyHash);
                        bucketBuffer.putInt(key.getVersion());
                        key.serialize(bucketBuffer);
                        bucketBuffer.putLong(value);
                        // increment count
                        setBucketEntryCount(entryCount+1);
                } else {
                    // bucket is full
                    // TODO expand into another bucket
                    throw new IllegalStateException("Bucket is full, could not store key: "+key);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** toString for debugging */
        @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
        @Override
        public String toString() {
            final int entryCount =  getBucketEntryCount();
            StringBuilder sb = new StringBuilder(
                "Bucket{bucketIndex="+getBucketIndex()+", entryCount="+entryCount+", nextBucketLocation="+getNextBucketLocation()+"\n"
            );
            try {
                for (int i = 0; i < entryCount; i++) {
                    bucketBuffer.clear();
                    int entryOffset = BUCKET_HEADER_SIZE + (i * entrySize);
                    int readHash = bucketBuffer.getInt(entryOffset);
                    bucketBuffer.position(entryOffset + Integer.BYTES);
                    int keySerializationVersion = bucketBuffer.getInt();
                    bucketBuffer.limit(bucketBuffer.position() + valueOffsetInEntry); // hack happens to be same number as keySize + Long.BYTES.
                    sb.append("    ENTRY[" + i + "] value= "+getValue(entryOffset)+" keyHash=" + readHash + " ver=" + keySerializationVersion + " key=" + getKey(entryOffset)+" hashCode="+getKey(entryOffset).hashCode()+"\n");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            bucketBuffer.clear();
            bucketBuffer.rewind();
            IntBuffer intBuf = bucketBuffer.asIntBuffer();
            int[] array = new int[intBuf.remaining()];
            intBuf.get(array);
            bucketBuffer.rewind();
            sb.append("} RAW DATA = "+ Arrays.toString(array));
            return sb.toString();
        }

    }
}