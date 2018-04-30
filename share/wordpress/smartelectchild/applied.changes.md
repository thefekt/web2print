
# Customizations

## Tweaks to  WordPress / Woocommerce setup

| destination       | change     | via
| :-------------    | :------------- | :----
| functions.php     | enqueue style |  wp_enqueue_style
| functions.php     | embed visionr iframe | woocommerce_before_single_product_summary
| functions.php     | [Wordpress -> Themes -> Pechatar specific] config page for pechatar specifics | setup_theme_admin_menus
| functions.php     | disable default product image |  woocommerce_show_product_images (remove) 
| functions.php     | store posted metadata to relevant cart item | woocommerce_before_add_to_cart_button
| functions.php     | cart item thumbnail customize | woocommerce_cart_item_thumbnail
| nginx.conf        | access VisionR & Wordpress & Socket.io connect same port (80) | upstream + location config
| Templatemela      | disable 'Top bar' default menu
| WP / Widgets      | disable footer content
| WP / Widgets      | remove sidebar content for products page
| functions.php     | disable woocommerce_product_tabs | woocommerce_product_tabs
| functions.php     | propagate cart item metadata to order | woocommerce_checkout_create_order_line_item
| functions.php     | download link at 'thanku page' | woocommerce_display_item_meta
| functions.php     | enable write_log logging function
| review-order.php  | styling of "Review Order" page
| cart.php          | lightbox show on cart view
