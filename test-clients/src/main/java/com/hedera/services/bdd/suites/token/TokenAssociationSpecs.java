package com.hedera.services.bdd.suites.token;

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

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.BaseErroringAssertsProvider;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.NoTokenTransfers.emptyTokenTransfers;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.FreezeNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Unfrozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Granted;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.KycNotApplicable;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TokenAssociationSpecs extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(TokenAssociationSpecs.class);

	public static final String FREEZABLE_TOKEN_ON_BY_DEFAULT = "TokenA";
	public static final String FREEZABLE_TOKEN_OFF_BY_DEFAULT = "TokenB";
	public static final String KNOWABLE_TOKEN = "TokenC";
	public static final String VANILLA_TOKEN = "TokenD";
	public static final String MULTI_KEY = "multiKey";
	public static final String TBD_TOKEN = "ToBeDeleted";

	public static void main(String... args) {
		final var spec = new TokenAssociationSpecs();

		spec.deferResultsSummary();
		spec.runSuiteAsync();
		spec.summarizeDeferredResults();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						treasuryAssociationIsAutomatic(),
						dissociateHasExpectedSemantics(),
						associatedContractsMustHaveAdminKeys(),
						expiredAndDeletedTokensStillAppearInContractInfo(),
						dissociationFromExpiredTokensAsExpected(),
						accountInfoQueriesAsExpected(),
						handlesUseOfDefaultTokenId(),
						contractInfoQueriesAsExpected(),
						dissociateHasExpectedSemanticsForDeletedTokens(),
						dissociateHasExpectedSemanticsForDissociatedContracts(),
						canDissociateFromDeletedTokenWithAlreadyDissociatedTreasury(),
				}
		);
	}

	@Override
	public boolean canRunAsync() {
		return true;
	}

	public HapiApiSpec handlesUseOfDefaultTokenId() {
		return defaultHapiSpec("HandlesUseOfDefaultTokenId")
				.given().when().then(
						tokenAssociate(DEFAULT_PAYER, "0.0.0")
								.hasKnownStatus(INVALID_TOKEN_ID)
				);
	}

	public HapiApiSpec associatedContractsMustHaveAdminKeys() {
		String misc = "someToken";
		String contract = "defaultContract";

		return defaultHapiSpec("AssociatedContractsMustHaveAdminKeys")
				.given(
						tokenCreate(misc)
				).when(
						contractCreate(contract).omitAdminKey()
				).then(
						tokenAssociate(contract, misc).hasKnownStatus(INVALID_SIGNATURE)
				);
	}

	public HapiApiSpec contractInfoQueriesAsExpected() {
		return defaultHapiSpec("ContractInfoQueriesAsExpected")
				.given(
						newKeyNamed("simple"),
						tokenCreate("a"),
						tokenCreate("b"),
						tokenCreate("c"),
						tokenCreate("tbd").adminKey("simple"),
						contractCreate("contract")
				).when(
						tokenAssociate("contract", "a", "b", "c", "tbd"),
						getContractInfo("contract")
								.hasToken(relationshipWith("a"))
								.hasToken(relationshipWith("b"))
								.hasToken(relationshipWith("c"))
								.hasToken(relationshipWith("tbd")),
						tokenDissociate("contract", "b"),
						tokenDelete("tbd")
				).then(
						getContractInfo("contract")
								.hasToken(relationshipWith("a"))
								.hasNoTokenRelationship("b")
								.hasToken(relationshipWith("c"))
								.hasToken(relationshipWith("tbd"))
								.logged()
				);
	}

	public HapiApiSpec accountInfoQueriesAsExpected() {
		return defaultHapiSpec("InfoQueriesAsExpected")
				.given(
						newKeyNamed("simple"),
						tokenCreate("a").decimals(1),
						tokenCreate("b").decimals(2),
						tokenCreate("c").decimals(3),
						tokenCreate("tbd").adminKey("simple").decimals(4),
						cryptoCreate("account").balance(0L)
				).when(
						tokenAssociate("account", "a", "b", "c", "tbd"),
						getAccountInfo("account")
								.hasToken(relationshipWith("a").decimals(1))
								.hasToken(relationshipWith("b").decimals(2))
								.hasToken(relationshipWith("c").decimals(3))
								.hasToken(relationshipWith("tbd").decimals(4)),
						tokenDissociate("account", "b"),
						tokenDelete("tbd")
				).then(
						getAccountInfo("account")
								.hasToken(relationshipWith("a").decimals(1))
								.hasNoTokenRelationship("b")
								.hasToken(relationshipWith("c").decimals(3))
								.hasToken(relationshipWith("tbd").decimals(4))
								.logged()
				);
	}

	public HapiApiSpec expiredAndDeletedTokensStillAppearInContractInfo() {
		final String contract = "nothingMattersAnymore";
		final String treasury = "something";
		final String expiringToken = "expiringToken";
		final long lifetimeSecs = 10;
		final long xfer = 123L;
		AtomicLong now = new AtomicLong();
		return defaultHapiSpec("ExpiredAndDeletedTokensStillAppearInContractInfo")
				.given(
						newKeyNamed("admin"),
						cryptoCreate(treasury),
						fileCreate("bytecode").path(ContractResources.FUSE_BYTECODE_PATH),
						contractCreate(contract).bytecode("bytecode").gas(300_000).via("creation"),
						withOpContext((spec, opLog) -> {
							var subOp = getTxnRecord("creation");
							allRunFor(spec, subOp);
							var record = subOp.getResponseRecord();
							now.set(record.getConsensusTimestamp().getSeconds());
						}),
						sourcing(() ->
								tokenCreate(expiringToken)
										.decimals(666)
										.adminKey("admin")
										.treasury(treasury)
										.expiry(now.get() + lifetimeSecs))
				).when(
						tokenAssociate(contract, expiringToken),
						cryptoTransfer(
								moving(xfer, expiringToken)
										.between(treasury, contract))
				).then(
						getAccountBalance(contract)
								.hasTokenBalance(expiringToken, xfer),
						getContractInfo(contract)
								.hasToken(relationshipWith(expiringToken)
										.freeze(FreezeNotApplicable)),
						sleepFor(lifetimeSecs * 1_000L),
						getAccountBalance(contract)
								.hasTokenBalance(expiringToken, xfer, 666),
						getContractInfo(contract)
								.hasToken(relationshipWith(expiringToken)
										.freeze(FreezeNotApplicable)),
						tokenDelete(expiringToken),
						getAccountBalance(contract)
								.hasTokenBalance(expiringToken, xfer),
						getContractInfo(contract)
								.hasToken(relationshipWith(expiringToken)
										.decimals(666)
										.freeze(FreezeNotApplicable))
				);
	}

	public HapiApiSpec dissociationFromExpiredTokensAsExpected() {
		final String treasury = "accountA";
		final String frozenAccount = "frozen";
		final String unfrozenAccount = "unfrozen";
		final String expiringToken = "expiringToken";
		long lifetimeSecs = 10;

		AtomicLong now = new AtomicLong();
		return defaultHapiSpec("DissociationFromExpiredTokensAsExpected")
				.given(
						newKeyNamed("freezeKey"),
						cryptoCreate(treasury),
						cryptoCreate(frozenAccount).via("creation"),
						cryptoCreate(unfrozenAccount).via("creation"),
						withOpContext((spec, opLog) -> {
							var subOp = getTxnRecord("creation");
							allRunFor(spec, subOp);
							var record = subOp.getResponseRecord();
							now.set(record.getConsensusTimestamp().getSeconds());
						}),
						sourcing(() ->
								tokenCreate(expiringToken)
										.freezeKey("freezeKey")
										.freezeDefault(true)
										.treasury(treasury)
										.initialSupply(1000L)
										.expiry(now.get() + lifetimeSecs))
				).when(
						tokenAssociate(unfrozenAccount, expiringToken),
						tokenAssociate(frozenAccount, expiringToken),
						tokenUnfreeze(expiringToken, unfrozenAccount),
						cryptoTransfer(
								moving(100L, expiringToken)
										.between(treasury, unfrozenAccount))
				).then(
						getAccountBalance(treasury)
								.hasTokenBalance(expiringToken, 900L),
						sleepFor(lifetimeSecs * 1_000L),
						tokenDissociate(treasury, expiringToken)
								.hasKnownStatus(ACCOUNT_IS_TREASURY),
						tokenDissociate(unfrozenAccount, expiringToken)
								.via("dissociateTxn"),
						getTxnRecord("dissociateTxn")
								.hasPriority(recordWith().tokenTransfers(new BaseErroringAssertsProvider<>() {
									@Override
									public ErroringAsserts<List<TokenTransferList>> assertsFor(
											HapiApiSpec spec) {
										return tokenXfers -> {
											try {
												assertEquals(
														1,
														tokenXfers.size(),
														"Wrong number of tokens transferred!");
												TokenTransferList xfers = tokenXfers.get(0);
												assertEquals(
														spec.registry().getTokenID(expiringToken),
														xfers.getToken(),
														"Wrong token transferred!");
												AccountAmount toTreasury = xfers.getTransfers(0);
												assertEquals(
														spec.registry().getAccountID(treasury),
														toTreasury.getAccountID(),
														"Treasury should come first!");
												assertEquals(
														100L,
														toTreasury.getAmount(),
														"Treasury should get 100 tokens back!");
												AccountAmount fromAccount = xfers.getTransfers(1);
												assertEquals(
														spec.registry().getAccountID(unfrozenAccount),
														fromAccount.getAccountID(),
														"Account should come second!");
												assertEquals(
														-100L,
														fromAccount.getAmount(),
														"Account should send 100 tokens back!");
											} catch (Throwable error) {
												return List.of(error);
											}
											return Collections.emptyList();
										};
									}
								})),
						getAccountBalance(treasury)
								.hasTokenBalance(expiringToken, 1000L),
						getAccountInfo(frozenAccount)
								.hasToken(relationshipWith(expiringToken)
										.freeze(Frozen)),
						tokenDissociate(frozenAccount, expiringToken)
								.hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
				);
	}

	public HapiApiSpec canDissociateFromDeletedTokenWithAlreadyDissociatedTreasury() {
		final String nonTreasuryAcquaintance = "1bFrozen";
		final long initialSupply = 100L;
		final long nonZeroXfer = 10L;
		final var treasuryDissoc = "treasuryDissoc";
		final var nonTreasuryDissoc = "nonTreasuryDissoc";

		return defaultHapiSpec("CanDissociateFromDeletedTokenWithAlreadyDissociatedTreasury")
				.given(
						newKeyNamed(MULTI_KEY),
						cryptoCreate(TOKEN_TREASURY).balance(0L),
						tokenCreate(TBD_TOKEN)
								.freezeKey(MULTI_KEY)
								.freezeDefault(false)
								.adminKey(MULTI_KEY)
								.initialSupply(initialSupply)
								.treasury(TOKEN_TREASURY),
						cryptoCreate(nonTreasuryAcquaintance).balance(0L)
				).when(
						tokenAssociate(nonTreasuryAcquaintance, TBD_TOKEN),
						cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN).between(TOKEN_TREASURY, nonTreasuryAcquaintance)),
						tokenFreeze(TBD_TOKEN, nonTreasuryAcquaintance),
						tokenDelete(TBD_TOKEN)
				).then(
						tokenDissociate(TOKEN_TREASURY, TBD_TOKEN).via(treasuryDissoc),
						tokenDissociate(nonTreasuryAcquaintance, TBD_TOKEN).via(nonTreasuryDissoc)
				);
	}

	public HapiApiSpec dissociateHasExpectedSemanticsForDeletedTokens() {
		final String tbdUniqToken = "UniqToBeDeleted";
		final String zeroBalanceFrozen = "0bFrozen";
		final String zeroBalanceUnfrozen = "0bUnfrozen";
		final String nonZeroBalanceFrozen = "1bFrozen";
		final String nonZeroBalanceUnfrozen = "1bUnfrozen";
		final long initialSupply = 100L;
		final long nonZeroXfer = 10L;
		final var zeroBalanceDissoc = "zeroBalanceDissoc";
		final var nonZeroBalanceDissoc = "nonZeroBalanceDissoc";
		final var uniqDissoc = "uniqDissoc";
		final var firstMeta = ByteString.copyFrom("FIRST".getBytes(StandardCharsets.UTF_8));
		final var secondMeta = ByteString.copyFrom("SECOND".getBytes(StandardCharsets.UTF_8));
		final var thirdMeta = ByteString.copyFrom("THIRD".getBytes(StandardCharsets.UTF_8));

		return defaultHapiSpec("DissociateHasExpectedSemanticsForDeletedTokens")
				.given(
						newKeyNamed(MULTI_KEY),
						cryptoCreate(TOKEN_TREASURY).balance(0L),
						tokenCreate(TBD_TOKEN)
								.adminKey(MULTI_KEY)
								.initialSupply(initialSupply)
								.treasury(TOKEN_TREASURY)
								.freezeKey(MULTI_KEY)
								.freezeDefault(true),
						tokenCreate(tbdUniqToken)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.treasury(TOKEN_TREASURY)
								.adminKey(MULTI_KEY)
								.supplyKey(MULTI_KEY)
								.initialSupply(0),
						cryptoCreate(zeroBalanceFrozen).balance(0L),
						cryptoCreate(zeroBalanceUnfrozen).balance(0L),
						cryptoCreate(nonZeroBalanceFrozen).balance(0L),
						cryptoCreate(nonZeroBalanceUnfrozen).balance(0L)
				).when(
						tokenAssociate(zeroBalanceFrozen, TBD_TOKEN),
						tokenAssociate(zeroBalanceUnfrozen, TBD_TOKEN),
						tokenAssociate(nonZeroBalanceFrozen, TBD_TOKEN),
						tokenAssociate(nonZeroBalanceUnfrozen, TBD_TOKEN),
						mintToken(tbdUniqToken, List.of(firstMeta, secondMeta, thirdMeta)),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(3),
						tokenUnfreeze(TBD_TOKEN, zeroBalanceUnfrozen),
						tokenUnfreeze(TBD_TOKEN, nonZeroBalanceUnfrozen),
						tokenUnfreeze(TBD_TOKEN, nonZeroBalanceFrozen),

						cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN).between(TOKEN_TREASURY, nonZeroBalanceFrozen)),
						cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN).between(TOKEN_TREASURY, nonZeroBalanceUnfrozen)),

						tokenFreeze(TBD_TOKEN, nonZeroBalanceFrozen),
						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(TBD_TOKEN, initialSupply - 2 * nonZeroXfer),
						tokenDelete(TBD_TOKEN),
						tokenDelete(tbdUniqToken)
				).then(
						tokenDissociate(zeroBalanceFrozen, TBD_TOKEN).via(zeroBalanceDissoc),
						tokenDissociate(zeroBalanceUnfrozen, TBD_TOKEN),
						tokenDissociate(nonZeroBalanceFrozen, TBD_TOKEN).via(nonZeroBalanceDissoc),
						tokenDissociate(nonZeroBalanceUnfrozen, TBD_TOKEN),
						tokenDissociate(TOKEN_TREASURY, tbdUniqToken).via(uniqDissoc),
						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(TBD_TOKEN, initialSupply - 2 * nonZeroXfer),
						getTxnRecord(zeroBalanceDissoc)
								.hasPriority(recordWith().tokenTransfers(emptyTokenTransfers())),
						getTxnRecord(nonZeroBalanceDissoc)
								.hasPriority(recordWith().tokenTransfers(changingFungibleBalances()
										.including(TBD_TOKEN, nonZeroBalanceFrozen, -nonZeroXfer))),
						getTxnRecord(uniqDissoc)
								.hasPriority(recordWith().tokenTransfers(changingFungibleBalances()
										.including(tbdUniqToken, TOKEN_TREASURY, -3))),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(0)
				);
	}

	public HapiApiSpec dissociateHasExpectedSemantics() {
		return defaultHapiSpec("DissociateHasExpectedSemantics")
				.given(flattened(
						basicKeysAndTokens()
				)).when(
						tokenCreate("tkn1")
								.treasury(TOKEN_TREASURY),
						tokenDissociate(TOKEN_TREASURY, "tkn1")
								.hasKnownStatus(ACCOUNT_IS_TREASURY),
						cryptoCreate("misc"),
						tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
								.hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
						tokenAssociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT, KNOWABLE_TOKEN),
						tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
								.hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
						tokenUnfreeze(FREEZABLE_TOKEN_ON_BY_DEFAULT, "misc"),
						cryptoTransfer(
								moving(1, FREEZABLE_TOKEN_ON_BY_DEFAULT)
										.between(TOKEN_TREASURY, "misc")),
						tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
								.hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES),
						cryptoTransfer(
								moving(1, FREEZABLE_TOKEN_ON_BY_DEFAULT)
										.between("misc", TOKEN_TREASURY)),
						tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
				).then(
						getAccountInfo("misc")
								.hasToken(relationshipWith(KNOWABLE_TOKEN))
								.hasNoTokenRelationship(FREEZABLE_TOKEN_ON_BY_DEFAULT)
								.logged()
				);
	}

	public HapiApiSpec dissociateHasExpectedSemanticsForDissociatedContracts() {
		final var multiKey = "multiKey";
		final var uniqToken = "UniqToken";
		final var contract = "1bUnfrozen";
		final var bytecode = "bytecode";
		final var firstMeta = ByteString.copyFrom("FIRST".getBytes(StandardCharsets.UTF_8));
		final var secondMeta = ByteString.copyFrom("SECOND".getBytes(StandardCharsets.UTF_8));
		final var thirdMeta = ByteString.copyFrom("THIRD".getBytes(StandardCharsets.UTF_8));

		return defaultHapiSpec("DissociateHasExpectedSemanticsForDissociatedContracts")
				.given(
						newKeyNamed(multiKey),
						cryptoCreate(TOKEN_TREASURY).balance(0L).maxAutomaticTokenAssociations(542),
						fileCreate(bytecode).path(ContractResources.FUSE_BYTECODE_PATH),
						contractCreate(contract).bytecode(bytecode).gas(300_000),
						tokenCreate(uniqToken)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.initialSupply(0)
								.supplyKey(multiKey)
								.treasury(TOKEN_TREASURY),
						mintToken(uniqToken, List.of(firstMeta, secondMeta, thirdMeta)),
						getAccountInfo(TOKEN_TREASURY).logged()
				).when(
						tokenAssociate(contract, uniqToken),
						tokenDissociate(contract, uniqToken)
				).then(
						cryptoTransfer(TokenMovement.movingUnique(uniqToken, 1L)
								.between(TOKEN_TREASURY, contract)
						).hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
				);
	}

	public HapiApiSpec treasuryAssociationIsAutomatic() {
		return defaultHapiSpec("TreasuryAssociationIsAutomatic")
				.given(
						basicKeysAndTokens()
				).when().then(
						getAccountInfo(TOKEN_TREASURY)
								.hasToken(
										relationshipWith(FREEZABLE_TOKEN_ON_BY_DEFAULT)
												.kyc(KycNotApplicable)
												.freeze(Unfrozen))
								.hasToken(
										relationshipWith(FREEZABLE_TOKEN_OFF_BY_DEFAULT)
												.kyc(KycNotApplicable)
												.freeze(Unfrozen))
								.hasToken(
										relationshipWith(KNOWABLE_TOKEN)
												.kyc(Granted)
												.freeze(FreezeNotApplicable))
								.hasToken(
										relationshipWith(VANILLA_TOKEN)
												.kyc(KycNotApplicable)
												.freeze(FreezeNotApplicable))
								.logged()
				);
	}

	public static HapiSpecOperation[] basicKeysAndTokens() {
		return new HapiSpecOperation[] {
				newKeyNamed("kycKey"),
				newKeyNamed("freezeKey"),
				cryptoCreate(TOKEN_TREASURY).balance(0L),
				tokenCreate(FREEZABLE_TOKEN_ON_BY_DEFAULT)
						.treasury(TOKEN_TREASURY)
						.freezeKey("freezeKey")
						.freezeDefault(true),
				tokenCreate(FREEZABLE_TOKEN_OFF_BY_DEFAULT)
						.treasury(TOKEN_TREASURY)
						.freezeKey("freezeKey")
						.freezeDefault(false),
				tokenCreate(KNOWABLE_TOKEN)
						.treasury(TOKEN_TREASURY)
						.kycKey("kycKey"),
				tokenCreate(VANILLA_TOKEN)
						.treasury(TOKEN_TREASURY)
		};
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
