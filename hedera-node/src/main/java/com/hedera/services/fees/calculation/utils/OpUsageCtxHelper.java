package com.hedera.services.fees.calculation.utils;

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

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.files.HFileMeta;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.usage.crypto.ExtantCryptoContext;
import com.hedera.services.usage.file.FileAppendMeta;
import com.hedera.services.usage.token.TokenOpsUsage;
import com.hedera.services.usage.token.meta.ExtantFeeScheduleContext;
import com.hedera.services.usage.token.meta.ExtantTokenContext;
import com.hederahashgraph.api.proto.java.Key;
import com.hedera.services.usage.token.meta.TokenBurnMeta;
import com.hedera.services.usage.token.meta.TokenMintMeta;
import com.hedera.services.usage.token.meta.TokenWipeMeta;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.swirlds.merkle.map.MerkleMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleAccountState.DEFAULT_MEMO;
import static com.hedera.services.state.submerkle.FcCustomFee.FeeType.FIXED_FEE;
import static com.hedera.services.state.submerkle.FcCustomFee.FeeType.FRACTIONAL_FEE;
import static com.hedera.services.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.fee.FeeBuilder.getAccountKeyStorageSize;

@Singleton
public class OpUsageCtxHelper {
	private static final ExtantFeeScheduleContext MISSING_FEE_SCHEDULE_UPDATE_CTX =
			new ExtantFeeScheduleContext(0, 0);

	private final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();

	private final Supplier<MerkleMap<EntityNum, MerkleToken>> tokens;
	private final StateView workingView;

	@Inject
	public OpUsageCtxHelper(
			StateView workingView,
			Supplier<MerkleMap<EntityNum, MerkleToken>> tokens
	) {
		this.tokens = tokens;
		this.workingView = workingView;
	}

	public FileAppendMeta metaForFileAppend(TransactionBody txn) {
		final var op = txn.getFileAppend();
		final var fileMeta = workingView.attrOf(op.getFileID());

		final var effCreationTime = txn.getTransactionID().getTransactionValidStart().getSeconds();
		final var effExpiration = fileMeta.map(HFileMeta::getExpiry).orElse(effCreationTime);
		final var effLifetime = effExpiration - effCreationTime;

		return new FileAppendMeta(op.getContents().size(), effLifetime);
	}

	public ExtantFeeScheduleContext ctxForFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody op) {
		final var key = EntityNum.fromTokenId(op.getTokenId());
		final var token = tokens.get().get(key);
		if (token == null) {
			return MISSING_FEE_SCHEDULE_UPDATE_CTX;
		}
		return new ExtantFeeScheduleContext(token.expiry(), curFeeScheduleReprSize(token.customFeeSchedule()));
	}

	public ExtantCryptoContext ctxForCryptoUpdate(TransactionBody txn) {
		final var op = txn.getCryptoUpdateAccount();
		ExtantCryptoContext cryptoContext;
		var info = workingView.infoForAccount(op.getAccountIDToUpdate());
		if (info.isPresent()) {
			var details = info.get();
			cryptoContext = ExtantCryptoContext.newBuilder()
					.setCurrentKey(details.getKey())
					.setCurrentMemo(details.getMemo())
					.setCurrentExpiry(details.getExpirationTime().getSeconds())
					.setCurrentlyHasProxy(details.hasProxyAccountID())
					.setCurrentNumTokenRels(details.getTokenRelationshipsCount())
					.setCurrentMaxAutomaticAssociations(details.getMaxAutomaticTokenAssociations())
					.build();
		} else {
			cryptoContext = ExtantCryptoContext.newBuilder()
					.setCurrentExpiry(txn.getTransactionID().getTransactionValidStart().getSeconds())
					.setCurrentMemo(DEFAULT_MEMO)
					.setCurrentKey(Key.getDefaultInstance())
					.setCurrentlyHasProxy(false)
					.setCurrentNumTokenRels(0)
					.setCurrentMaxAutomaticAssociations(0)
					.build();
		}
		return cryptoContext;
	}

	// get the TokenInfo from StateView and fill into ExtantTokenContext
	public ExtantTokenContext ctxForTokenUpdate(TransactionBody txn) {
		final var op = txn.getTokenUpdate();
		var tokenInfoMaybe = workingView.infoForToken(op.getToken());
		if(tokenInfoMaybe.isPresent()) {
			final var tokenInfo = tokenInfoMaybe.get();
			return ExtantTokenContext.newBuilder()
					.setExistingNameLen(tokenInfo.getName().length())
					.setExistingSymLen(tokenInfo.getSymbol().length())
					.setExistingMemoLen(tokenInfo.getMemo().length())
					.setExistingExpiry(tokenInfo.hasExpiry() ?
							tokenInfo.getExpiry().getSeconds() : 0)
					.setHasAutoRenewalAccount(tokenInfo.hasAutoRenewAccount())
					.setExistingAdminKeyLen(tokenInfo.hasAdminKey() ?
							getAccountKeyStorageSize(tokenInfo.getAdminKey()) : 0)
					.setExistingKycKeyLen(tokenInfo.hasKycKey() ?
							getAccountKeyStorageSize(tokenInfo.getKycKey()) : 0)
					.setExistingFreezeKeyLen(tokenInfo.hasFreezeKey() ?
							getAccountKeyStorageSize(tokenInfo.getFreezeKey()) : 0)
					.setExistingSupplyKeyLen(tokenInfo.hasSupplyKey() ?
							getAccountKeyStorageSize(tokenInfo.getSupplyKey()) : 0)
					.setExistingWipeKeyLen(tokenInfo.hasWipeKey() ?
							getAccountKeyStorageSize(tokenInfo.getWipeKey()) : 0)
					.setExistingFeeScheduleKeyLen(tokenInfo.hasFeeScheduleKey() ?
							getAccountKeyStorageSize(tokenInfo.getFeeScheduleKey()) : 0)
					.setExistingKycKeyLen(tokenInfo.hasKycKey() ?
							getAccountKeyStorageSize(tokenInfo.getKycKey()) : 0)
					.build();
		} else {
			return ExtantTokenContext.newBuilder()
					.setExistingNameLen(0)
					.setExistingSymLen(0)
					.setExistingMemoLen(0)
					.setExistingExpiry(0)
					.setHasAutoRenewalAccount(false)
					.setExistingAdminKeyLen(0)
					.setExistingKycKeyLen(0)
					.setExistingFreezeKeyLen(0)
					.setExistingSupplyKeyLen(0)
					.setExistingFeeScheduleKeyLen(0)
					.setExistingWipeKeyLen(0)
					.build();
		}
	}

	// These two can be removed
	public TokenBurnMeta metaForTokenBurn(TxnAccessor accessor) {
		return TOKEN_OPS_USAGE_UTILS.tokenBurnUsageFrom(accessor.getTxn(), accessor.getSubType());
	}

	public TokenWipeMeta metaForTokenWipe(TxnAccessor accessor) {
		return TOKEN_OPS_USAGE_UTILS.tokenWipeUsageFrom(accessor.getTxn(), accessor.getSubType());
	}

	public TokenMintMeta metaForTokenMint(TxnAccessor accessor) {
		final var subType = accessor.getSubType();

		long lifeTime = 0L;
		if (subType == TOKEN_NON_FUNGIBLE_UNIQUE) {
			final var token = accessor.getTxn().getTokenMint().getToken();
			final var now = accessor.getTxnId().getTransactionValidStart().getSeconds();
			final var tokenIfPresent = workingView.tokenWith(token);
			lifeTime = tokenIfPresent.map(t -> Math.max(0L, t.expiry() - now)).orElse(0L);
		}
		return TOKEN_OPS_USAGE_UTILS.tokenMintUsageFrom(accessor.getTxn(), subType, lifeTime);
	}

	private int curFeeScheduleReprSize(List<FcCustomFee> feeSchedule) {
		int numFixedHbarFees = 0;
		int numFixedHtsFees = 0;
		int numFractionalFees = 0;
		int numRoyaltyNoFallbackFees = 0;
		int numRoyaltyHtsFallbackFees = 0;
		int numRoyaltyHbarFallbackFees = 0;
		for (var fee : feeSchedule) {
			if (fee.getFeeType() == FIXED_FEE) {
				if (fee.getFixedFeeSpec().getTokenDenomination() != null) {
					numFixedHtsFees++;
				} else {
					numFixedHbarFees++;
				}
			} else if (fee.getFeeType() == FRACTIONAL_FEE) {
				numFractionalFees++;
			} else {
				final var royaltyFee = fee.getRoyaltyFeeSpec();
				final var fallbackFee = royaltyFee.getFallbackFee();
				if (fallbackFee != null) {
					if (fallbackFee.getTokenDenomination() != null) {
						numRoyaltyHtsFallbackFees++;
					} else {
						numRoyaltyHbarFallbackFees++;
					}
				} else {
					numRoyaltyNoFallbackFees++;
				}
			}
		}
		return tokenOpsUsage.bytesNeededToRepr(
				numFixedHbarFees,
				numFixedHtsFees,
				numFractionalFees,
				numRoyaltyNoFallbackFees,
				numRoyaltyHtsFallbackFees,
				numRoyaltyHbarFallbackFees);
	}
}
