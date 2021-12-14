package com.hedera.services.bdd.spec.queries;

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
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseHeader;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionGetReceiptQuery;
import com.hederahashgraph.api.proto.java.TransactionID;

import java.lang.reflect.Method;
import java.util.Arrays;

import static com.hedera.services.bdd.spec.PropertySource.asAccountString;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;

public class QueryUtils {
	public static QueryHeader answerHeader(Transaction txn) {
		return QueryHeader.newBuilder()
				.setResponseType(ANSWER_ONLY).setPayment(txn).build();
	}

	public static QueryHeader answerCostHeader(Transaction txn) {
		return QueryHeader.newBuilder()
				.setResponseType(COST_ANSWER).setPayment(txn).build();
	}

	public static Query txnReceiptQueryFor(TransactionID txnId) {
		return txnReceiptQueryFor(txnId, false);
	}

	public static Query txnReceiptQueryFor(TransactionID txnId, boolean includeDuplicates) {
		return Query.newBuilder()
				.setTransactionGetReceipt(
						TransactionGetReceiptQuery.newBuilder()
								.setHeader(answerHeader(Transaction.getDefaultInstance()))
								.setTransactionID(txnId)
								.setIncludeDuplicates(includeDuplicates)
								.build()
				).build();
	}

	public static long reflectForCost(Response response) throws Throwable {
		return (long) reflectForHeaderField(response, "cost");
	}

	public static ResponseCodeEnum reflectForPrecheck(Response response) throws Throwable {
		return (ResponseCodeEnum) reflectForHeaderField(response, "nodeTransactionPrecheckCode");
	}

	private static Object reflectForHeaderField(Response response, String field) throws Throwable {
		String getterName = Arrays
				.stream(Response.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> !"hashCode".equals(name) && name.startsWith("has"))
				.filter(name -> {
					try {
						return (Boolean) Response.class.getMethod(name).invoke(response);
					} catch (Exception ignore) {
					}
					return false;
				})
				.map(name -> name.replace("has", "get"))
				.findAny()
				.get();
		Method getter = Response.class.getMethod(getterName);
		Class<?> getterClass = getter.getReturnType();
		Method headerMethod = getterClass.getMethod("getHeader");
		ResponseHeader header = (ResponseHeader) headerMethod.invoke(getter.invoke(response));
		Method fieldGetter = ResponseHeader.class.getMethod(asGetter(field));
		return fieldGetter.invoke(header);
	}

	public static String asGetter(String field) {
		return "get" + field.substring(0, 1).toUpperCase() + field.substring(1);
	}

	public static String lookUpAccountWithAlias(HapiApiSpec spec, String aliasKey) {
		final var lookedUpKey = spec.registry().getKey(aliasKey).toByteString().toStringUtf8();
		return asAccountString(spec.registry().getAccountID(lookedUpKey));
	}
}
