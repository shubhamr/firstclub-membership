package com.firstclub.membership.model;

/**
 * Category of a benefit. The type drives how downstream systems (checkout, delivery, support)
 * interpret the benefit's params. New benefit families are added here; per-tier tuning stays in
 * data.
 */
public enum BenefitType {
  FREE_DELIVERY,
  EXTRA_DISCOUNT,
  EXCLUSIVE_DEALS,
  EXCLUSIVE_COUPONS,
  PRIORITY_SUPPORT
}
