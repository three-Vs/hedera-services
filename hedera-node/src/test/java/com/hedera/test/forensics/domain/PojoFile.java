package com.hedera.test.forensics.domain;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.hedera.services.state.merkle.MerkleOptionalBlob;

import java.util.Map;

@JsonPropertyOrder({
		"path",
		"blobId",
		"blobHash",
		"blobDeleted",
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PojoFile {
	private String path;
	private String blobHash;
	private boolean blobDeleted;

	public static PojoFile fromEntry(Map.Entry<String, MerkleOptionalBlob> e) {
		return from(e.getKey(), e.getValue());
	}

	public static PojoFile from(String sk, MerkleOptionalBlob value) {
		var pojo = new PojoFile();
		pojo.setPath(sk);
		pojo.setBlobHash(value.getDelegate().getHash().toString());
		pojo.setBlobDeleted(value.getDelegate().isReleased());
		return pojo;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getBlobHash() {
		return blobHash;
	}

	public void setBlobHash(String blobHash) {
		this.blobHash = blobHash;
	}

	public boolean isBlobDeleted() {
		return blobDeleted;
	}

	public void setBlobDeleted(boolean blobDeleted) {
		this.blobDeleted = blobDeleted;
	}
}
