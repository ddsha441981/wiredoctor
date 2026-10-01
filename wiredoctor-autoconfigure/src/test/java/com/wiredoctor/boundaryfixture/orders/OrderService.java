/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.boundaryfixture.orders;

import com.wiredoctor.boundaryfixture.billing.BillingRepo;

/**
 * WD-705 test fixture: a bean of the {@code orders} module that reaches into
 * {@code billing}'s internal {@link BillingRepo} — the cross-module edge into a
 * non-API package that the boundary detector flags.
 */
public class OrderService {

    private final BillingRepo billingRepo;

    public OrderService(BillingRepo billingRepo) {
        this.billingRepo = billingRepo;
    }
}
