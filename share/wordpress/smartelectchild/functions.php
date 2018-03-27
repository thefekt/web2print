<?php

function pechatar_theme_enqueue_styles() {
        // as per https://codex.wordpress.org/Child_Themes
        $parent_style = 'smartelect-style-home';

        wp_enqueue_style( $parent_style,
            get_template_directory_uri() . '/style.css' );

        wp_enqueue_style( 'pechatar-style',
            get_stylesheet_directory_uri() . '/style.css',
            array( $parent_style ),
            wp_get_theme()->get('Version')
        );
    }

add_action( 'wp_enqueue_scripts', 'pechatar_theme_enqueue_styles' );

/**
 * Opening div for our content wrapper
 */
add_action('woocommerce_before_single_product_summary', 'pechatar_open_div', 5);

function pechatar_open_div() {
    echo '<h3> Design </h3>';
    $visionr_location = get_option("visionr_location").'/#/print?tpl=8090';
    global $product;
    echo 'PID: '.$product->get_id();
    echo '<div class="visionr-container"><iframe src="'.$visionr_location.'"> </iframe></h3>';
}

/**
 * Closing div for our content wrapper
 */
add_action('woocommerce_before_single_product_summary', 'pechatar_close_div', 50);

function pechatar_close_div() {
    echo '</div>';
}

remove_action( 'woocommerce_before_single_product_summary', 'woocommerce_show_product_images', 20 );

function pechatarnik_specific_config() {
    if (!current_user_can('manage_options')) {
        wp_die('You do not have sufficient permissions to access this page.');
    }

    $visionr_location = get_option("visionr_location");

    if (isset($_POST["update_settings"])) {
        $visionr_location = esc_attr($_POST["visionr_location"]);
        update_option("visionr_location", $visionr_location);

        ?>
            <div id="message" class="updated">Settings saved</div>
        <?php
    }
    ?>

        <form method="POST" action="">
            <h2> Pechatarnik customization </h2>
            <input type="hidden" name="update_settings" value="Y" />
            <label for="num_elements">
                URL of underlying VISIONR :
            </label>

            <input type="text" name="visionr_location" value="<?php echo $visionr_location?>" />

            <p>
                <input type="submit" value="Save settings" class="button-primary"/>
            </p>
        </form>
    <?php
}


//////////////////////////////////////////

function woocommerce_new_order() {


}

/**
 * Output engraving field.
 */

function pechatar_output_local_storage() {
    global $product;

    ?>
    <script>
      window.addEventListener("message", receiveMessage, false);

      function receiveMessage(event) {
        if (!(event.origin === "http://localhost") || (event.origin === "http://localhost:4300") )
          return;

        document.getElementById('item-config').value = event.data;
      }
    </script>
    <div class="iconic-engraving-field">
        <input type="hidden" id="item-config" name="item-config" value="<?php echo $lvarser ?>">
    </div>

    <?php
}

add_action( 'woocommerce_before_add_to_cart_button', 'pechatar_output_local_storage', 10 );

/**
 * Add engraving text to cart item.
 *
 * @param array $cart_item_data
 * @param int   $product_id
 * @param int   $variation_id
 *
 * @return array
 */

function pechatar_add_meta_to_cart_item( $cart_item_data, $product_id, $variation_id ) {
    $item_meta = filter_input( INPUT_POST, 'item-config' );

    $cart_item_data['pechatar-meta'] = $item_meta;

    return $cart_item_data;
}

add_filter( 'woocommerce_add_cart_item_data', 'pechatar_add_meta_to_cart_item', 10, 3 );

/**
 * Display engraving text in the cart.
 *
 * @param array $item_data
 * @param array $cart_item
 *
 * @return array
 */
function display_text_cart( $item_data, $cart_item ) {
    if ( empty( $cart_item['pechatar-meta'] ) ) {
        return $item_data;
    }

    $item_data[] = array(
        'key'     => __( 'Meta', 'pechatar' ),
        'value'   => wc_clean( $cart_item['pechatar-meta'] ),
        'display' => '',
    );

    return $item_data;
}

add_filter( 'woocommerce_get_item_data', 'display_text_cart', 10, 2 );


function setup_theme_admin_menus() {
    add_submenu_page('themes.php',
        'Pechatarnik Specific Configuration', 'Pechatarnik Specific', 'manage_options',
        'pechatarnik-specific-configuration', 'pechatarnik_specific_config');
}

add_action("admin_menu", "setup_theme_admin_menus");

?>
