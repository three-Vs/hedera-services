package com.hedera.services.bdd.suites.crypto;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

import static com.hedera.services.bdd.spec.HapiApiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;

public class HelloWorldSpec extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(HelloWorldSpec.class);

	public static void main(String... args) {
		new HelloWorldSpec().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[]{
						balancesChangeOnTransfer(),
				}
		);
	}

	private HapiApiSpec balancesChangeOnTransfer() {
		return defaultHapiSpec("BalancesChangeOnTransfer")
				.given(
						cryptoCreate("sponsor"),
						cryptoCreate("beneficiary"),
						balanceSnapshot("sponsorBefore", "sponsor"),
						balanceSnapshot("beneficiaryBefore", "beneficiary")
				).when(
						cryptoTransfer(tinyBarsFromTo("sponsor", "beneficiary", 1L))
								.payingWith(GENESIS)
								.memo("Hello World!")
				).then(
						getAccountBalance("sponsor")
								.hasTinyBars(changeFromSnapshot("sponsorBefore", -1L)),
						getAccountBalance("beneficiary")
								.hasTinyBars(changeFromSnapshot("beneficiaryBefore", +1L))
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
