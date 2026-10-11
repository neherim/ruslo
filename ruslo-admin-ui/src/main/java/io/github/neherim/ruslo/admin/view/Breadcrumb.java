package io.github.neherim.ruslo.admin.view;

/**
 * Breadcrumb item.
 *
 * @param href  link of a parent page item
 * @param title full text for the tooltip
 */
public record Breadcrumb(String label, String href, String title, boolean current) {

    /**
     * Returns a link to a parent page.
     */
    public static Breadcrumb link(String label, String href) {
        return new Breadcrumb(label, href, label, false);
    }

    /**
     * Returns the current page item.
     */
    public static Breadcrumb current(String label) {
        return new Breadcrumb(label, null, label, true);
    }
}
