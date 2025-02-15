package com.hedera.services.usage.crypto;

/*-
 * ‌
 * Hedera Services API Fees
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

import com.hedera.services.usage.QueryUsage;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;

import java.nio.charset.StandardCharsets;

import static com.hedera.services.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.getAccountKeyStorageSize;

public final class CryptoGetInfoUsage extends QueryUsage {
	private CryptoGetInfoUsage(final Query query) {
		super(query.getCryptoGetInfo().getHeader().getResponseType());
		addTb(BASIC_ENTITY_ID_SIZE);
		addRb(CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr());
	}

	public static CryptoGetInfoUsage newEstimate(final Query query) {
		return new CryptoGetInfoUsage(query);
	}

	public CryptoGetInfoUsage givenCurrentKey(final Key key) {
		addRb(getAccountKeyStorageSize(key));
		return this;
	}

	public CryptoGetInfoUsage givenCurrentMemo(final String memo) {
		addRb(memo.getBytes(StandardCharsets.UTF_8).length);
		return this;
	}

	public CryptoGetInfoUsage givenCurrentTokenAssocs(final int count) {
		addRb(count * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr());
		return this;
	}

	public CryptoGetInfoUsage givenCurrentlyUsingProxy() {
		addRb(BASIC_ENTITY_ID_SIZE);
		return this;
	}
}
