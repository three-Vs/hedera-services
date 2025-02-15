package com.hedera.services.fees.calculation.contract.queries;

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
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.fee.SmartContractFeeBuilder;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;

@Singleton
public final class GetBytecodeResourceUsage implements QueryResourceUsageEstimator {
	private static final byte[] EMPTY_BYTECODE = new byte[0];

	private final SmartContractFeeBuilder usageEstimator;

	@Inject
	public GetBytecodeResourceUsage(final SmartContractFeeBuilder usageEstimator) {
		this.usageEstimator = usageEstimator;
	}

	@Override
	public boolean applicableTo(final Query query) {
		return query.hasContractGetBytecode();
	}

	@Override
	public FeeData usageGiven(final Query query, final StateView view, final Map<String, Object> ignoreCtx) {
		return usageGivenType(query, view, query.getContractGetBytecode().getHeader().getResponseType());
	}

	@Override
	public FeeData usageGivenType(final Query query, final StateView view, final ResponseType type) {
		final var op = query.getContractGetBytecode();
		final var bytecode = view.bytecodeOf(op.getContractID()).orElse(EMPTY_BYTECODE);
		return usageEstimator.getContractByteCodeQueryFeeMatrices(bytecode.length, type);
	}
}
