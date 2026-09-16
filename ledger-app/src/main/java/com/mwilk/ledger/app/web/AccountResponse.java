package com.mwilk.ledger.app.web;

/** @param balance current balance in minor units */
public record AccountResponse(String id, long balance) {
}
