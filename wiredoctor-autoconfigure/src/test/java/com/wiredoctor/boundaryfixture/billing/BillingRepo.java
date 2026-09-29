/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.boundaryfixture.billing;

/**
 * WD-705 test fixture: an <em>internal</em> (non-API) bean of the {@code billing}
 * module. Reaching into it from another module is a boundary violation.
 */
public class BillingRepo {
}
