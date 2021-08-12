package com.hedera.services.state.merkle.v3.files;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.hedera.services.state.merkle.v3.V3TestUtils.deleteDirectoryAndContents;
import static com.hedera.services.state.merkle.v3.files.DataFileCommon.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SameParameterValue")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DataFileReaderCollectionVariableSizeDataTest {
    private static final Instant TEST_START = Instant.now();
    private static final int MAX_DATA_SIZE = Integer.BYTES * 1001;
    private static Path tempFileDir;
    private static DataFileCollection fileCollection;
    private static final List<Long> storedOffsets = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean mergeComplete = new AtomicBoolean(false);
    private static final DataFileReaderFactory dataFileReaderFactory = new DataFileReaderFactory() {
        @Override
        public DataFileReader newDataFileReader(Path path) throws IOException {
            return new DataFileReaderThreadLocal(path);
        }

        @Override
        public DataFileReader newDataFileReader(Path path, DataFileMetadata metadata) throws IOException {
            return new DataFileReaderThreadLocal(path,metadata);
        }
    };

    @Test
    @Order(1)
    public void createDataFileCollection() throws Exception {
        // get non-existent temp file
        tempFileDir = Files.createTempDirectory("DataFileTest");
        System.out.println("tempFileDir.toAbsolutePath() = " + tempFileDir.toAbsolutePath());
        deleteDirectoryAndContents(tempFileDir);
        // create collection
        fileCollection = new DataFileCollection(tempFileDir, "TestDataStore", VARIABLE_DATA_SIZE,
                null, dataFileReaderFactory);
    }

    @Test
    @Order(2)
    public void create10x100ItemFiles() throws Exception {
        int count = 0;
        for (int f = 0; f < 10; f++) {
            fileCollection.startWriting();
            // put in 1000 items
            ByteBuffer tempData = ByteBuffer.allocate(MAX_DATA_SIZE);
            for (int i = count; i < count+100; i++) {
                // prep data buffer
                tempData.clear();
                final int dataIntCount = i+1;
                for (int d = 0; d < dataIntCount; d++) {
                    tempData.putInt(i);
                }
                tempData.flip();
                // store in file
                storedOffsets.add(fileCollection.storeData(i, tempData));
            }
            fileCollection.endWriting(0,count+100);
            count += 100;
        }
        // check 10 files were created
        assertEquals(10,Files.list(tempFileDir).count());
        Files.list(tempFileDir).forEach(file -> {
            try {
                System.out.println(file+" -- size="+Files.size(file));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    @Test
    @Order(3)
    public void check1000() throws Exception {
        check1000Impl();
    }

    @Test
    @Order(4)
    public void checkFilesStates() throws Exception {
        for (int f = 0; f < 10; f++) {
            DataFileReader dataFileReader = fileCollection.getDataFile(f);
            DataFileMetadata metadata = dataFileReader.getMetadata();
            assertFalse(metadata.isMergeFile());
            assertEquals(f, metadata.getIndex());
            assertTrue(metadata.getCreationDate().isAfter(TEST_START));
            assertTrue(metadata.getCreationDate().isBefore(Instant.now()));
            assertEquals(0, metadata.getMinimumValidKey());
            assertEquals((f+1)*100, metadata.getMaximumValidKey());
            assertEquals(100, metadata.getDataItemCount());
            assertTrue(dataFileReader.getSize() % DataFileCommon.PAGE_SIZE == 0);
        }
    }

    @Test
    @Order(50)
    public void closeAndReopen() throws Exception {
        fileCollection.close();
        fileCollection = new DataFileCollection(tempFileDir, "TestDataStore", VARIABLE_DATA_SIZE,
                null, dataFileReaderFactory);
    }

    @Test
    @Order(51)
    public void check1000AfterReopen() throws Exception {
        check1000Impl();
    }

    @Test
    @Order(100)
    public void merge() throws Exception {
        IntStream.range(0,2).parallel().forEach(thread -> {
            if (thread == 0) { // checking thread, keep reading and checking data all the time while we are merging
                while(!mergeComplete.get()) {
                    try {
                        check1000Impl();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else if (thread == 1) { // move thread
                try {
                    fileCollection.mergeFile(moves -> {
                        assertEquals(1000,moves.size());
                        for(long[] move: moves) {
                            System.out.printf("move from file %d item %d -> file %d item %d\n",
                                    DataFileCommon.fileIndexFromDataLocation(move[0]),
                                    DataFileCommon.byteOffsetFromDataLocation(move[0]),
                                    DataFileCommon.fileIndexFromDataLocation(move[1]),
                                    DataFileCommon.byteOffsetFromDataLocation(move[1])
                            );
                            int index = storedOffsets.indexOf(move[0]);
                            storedOffsets.set(index, move[1]);
                        }
                    }, fileCollection.getAllFullyWrittenFiles(Integer.MAX_VALUE));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mergeComplete.set(true);
            }
        });
        // check we only have 1 file left
        assertEquals(1,Files.list(tempFileDir).count());
    }

    @Test
    @Order(101)
    public void check1000AfterMerge() throws Exception {
        check1000Impl();
    }

    @Test
    @Order(1000)
    public void cleanup() throws Exception {
        fileCollection.close();
        // clean up and delete files
        deleteDirectoryAndContents(tempFileDir);
    }

    private static void check1000Impl() throws Exception {
        // now read back all the data and check all data
        ByteBuffer tempResult = ByteBuffer.allocate(KEY_SIZE + MAX_DATA_SIZE);
        for (int i = 0; i < 1000; i++) {
            long storedOffset = storedOffsets.get(i);
            tempResult.clear();
            // read
            fileCollection.readData(storedOffset,tempResult, DataFileReader.DataToRead.KEY_VALUE);
            // check all the data
            tempResult.rewind();
            assertEquals(i, tempResult.getLong()); // key
            final int dataIntCount = i+1;
            for (int d = 0; d < dataIntCount; d++) {
                assertEquals(i, tempResult.getInt());
            }
        }
    }
}