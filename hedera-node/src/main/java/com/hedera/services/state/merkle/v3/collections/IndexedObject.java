package com.hedera.services.state.merkle.v3.collections;

/**
 * Simple interface for an object that has an index
 */
public interface IndexedObject {

    /**
     * Get object index, the index is a ordered integer identifying the object
     *
     * @return this object's index
     */
    int getIndex();
}