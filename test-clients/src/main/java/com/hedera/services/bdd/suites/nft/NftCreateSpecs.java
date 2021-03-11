package com.hedera.services.bdd.suites.nft;

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

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nftCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;

public class NftCreateSpecs extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(NftCreateSpecs.class);

	public static void main(String... args) {
		new NftCreateSpecs().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
				simpleNftCreation(),
				accountCanBeRepeatTreasury(),
		});
	}

	private HapiApiSpec simpleNftCreation() {
		return defaultHapiSpec("SimpleNftCreation")
				.given(
						cryptoCreate("Smithsonian")
				).when(
						nftCreate("naturalHistory")
								.memo("NFT with first run minting of three serial numbers.")
								.via("creation")
								.payingWith("Smithsonian")
								.initialSerialNos(3)
								.logged()
				).then(
						getTxnRecord("creation").logged()
				);
	}

	private HapiApiSpec accountCanBeRepeatTreasury() {
		return defaultHapiSpec("AccountCanBeRepeatTreasury")
				.given(
						cryptoCreate("Smithsonian")
				).when(
						nftCreate("naturalHistory")
								.memo("Natural history NFT")
								.treasury("Smithsonian")
								.initialSerialNos(3),
						nftCreate("patronStatus")
								.memo("Patron status NFT")
								.treasury("Smithsonian")
								.initialSerialNos(10),
						nftCreate("annualMembership")
								.memo("Annual membership NFT")
								.treasury("Smithsonian")
								.initialSerialNos(1)
				).then(
						getAccountBalance("Smithsonian").logged()
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}