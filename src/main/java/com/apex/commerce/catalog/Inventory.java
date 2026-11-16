package com.apex.commerce.catalog;

/**
 * All stock changes go through here. Updates are single conditional SQL
 * statements, so two shoppers can never both buy the last item. For a variant,
 * the variant's stock and the product's total move together.
 */
public final class Inventory {

    private Inventory() {}

    /** Takes {@code qty} out of stock; false if there isn't enough. */
    public static boolean reserve(Long productId, Long variantId, int qty) {
        if (variantId != null) {
            if (ProductVariant.update("stock = stock - ?1 where id = ?2 and stock >= ?1", qty, variantId) == 0) {
                return false;
            }
            Product.update("stock = stock - ?1 where id = ?2", qty, productId);
            return true;
        }
        return Product.update("stock = stock - ?1 where id = ?2 and stock >= ?1", qty, productId) > 0;
    }

    /** Puts {@code qty} back (cancelled or abandoned orders). Deleted products/variants are skipped. */
    public static void release(Long productId, Long variantId, int qty) {
        if (productId == null) {
            return;
        }
        if (variantId != null) {
            ProductVariant.update("stock = stock + ?1 where id = ?2", qty, variantId);
        }
        Product.update("stock = stock + ?1 where id = ?2", qty, productId);
    }
}
