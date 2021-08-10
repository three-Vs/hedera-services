package com.hedera.services.txns.validation;

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

import com.hedera.test.utils.TxnUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PureValidationTest {
	Instant now = Instant.now();
	long impossiblySmallSecs = Instant.MIN.getEpochSecond() - 1;
	int impossiblySmallNanos = -1;
	long impossiblyBigSecs = Instant.MAX.getEpochSecond() + 1;
	int impossiblyBigNanos = 1_000_000_000;

	@Test
	void mapsSensibleTimestamp() {
		// given:
		var proto = TxnUtils.timestampFrom(now.getEpochSecond(), now.getNano());

		// expect:
		assertEquals(now, PureValidation.asCoercedInstant(proto));
	}

	@Test
	void coercesTooSmallTimestamp() {
		// given:
		var proto = TxnUtils.timestampFrom(impossiblySmallSecs, impossiblySmallNanos);

		// expect:
		assertEquals(Instant.MIN, PureValidation.asCoercedInstant(proto));
	}

	@Test
	void coercesTooBigTimestamp() {
		// given:
		var proto = TxnUtils.timestampFrom(impossiblyBigSecs, impossiblyBigNanos);

		// expect:
		assertEquals(Instant.MAX, PureValidation.asCoercedInstant(proto));
	}

}
