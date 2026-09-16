package com.mwilk.ledger.app;

import com.mwilk.ledger.core.Ledger;
import com.mwilk.ledger.core.inmemory.InMemoryLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the storage implementation. Swapping to a persistent engine means replacing this one bean. */
@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {

    @Bean
    Ledger ledger() {
        return new InMemoryLedger();
    }
}
