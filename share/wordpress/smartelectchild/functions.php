<?php

// from https://www.elegantthemes.com/blog/tips-tricks/using-the-wordpress-debug-log
global $PET_DEBUG;
$PET_DEBUG =  get_option("visionr_DEBUG") > 0 ? true : false;

if ( ! function_exists('write_log')) {
   function write_log ( $log )  {
      if ( is_array( $log ) || is_object( $log ) ) {
         error_log( print_r( $log, true ) );
      } else {
         error_log( $log );
      }
   }
}

////////////////////////////////////////////////////////////////////////////////

// docker cp share/wordpress/storechild/functions.php friendly_khorana:/data/www/wp-content/themes/smartelectchild/

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

////////////////////////////////////////////////////////////////////////////////

/* output design editor markup */

add_action('woocommerce_before_single_product_summary', 'pechatar_open_div', 5);

function pechatar_open_div() {
    global $PET_DEBUG;
    global $product;

    //echo '<h3> Дизайн и съдържание </h3>';
    $pid = $product->get_sku(); // or get_id()
    $baseURL = home_url();

    $visionr_location = get_option("visionr_location")."/#/print?".join([
      "tpl=$pid",
      "DEBUG=".(int)$PET_DEBUG,
      "baseURL=".$baseURL], '&');
    //if ($PET_DEBUG) echo "<B> PID: $pid </b>";
    echo '<div class="visionr-container"><iframe src="'.$visionr_location.'"> </iframe></h3>';
}

add_action('woocommerce_before_single_product_summary', 'pechatar_close_div', 50);

function pechatar_close_div() {
    echo '</div>';
}

////////////////////////////////////////////////////////////////////////////////

/* specific config */

remove_action( 'woocommerce_before_single_product_summary', 'woocommerce_show_product_images', 20 );

function pechatarnik_specific_config() {
    if (!current_user_can('manage_options')) {
        wp_die('You do not have sufficient permissions to access this page.');
    }

    $visionr_location = get_option("visionr_location");
    $visionr_DEBUG = get_option("visionr_DEBUG");

    if (isset($_POST["update_settings"])) {
        $visionr_location = esc_attr($_POST["visionr_location"]);
        update_option("visionr_location", $visionr_location);
        $visionr_DEBUG = esc_attr($_POST["visionr_DEBUG"]);
        update_option("visionr_DEBUG", $visionr_DEBUG == 'true' ? true : false);

        ?>
            <div id="message" class="updated">Settings saved</div>
        <?php
    }
    ?>

    <form method="POST" action="">
        <h2> Pechatarnik customization </h2>
        <input type="hidden" name="update_settings" value="Y" />
        <ul>
          <li>
            <label for="visionr_location">
                URL of underlying VISIONR :
            </label>
          </li>
          <li>
            <input type="text" name="visionr_location" value="<?php echo $visionr_location?>" />
          </li>

          <li>
            <label for="visionr_DEBUG">
              debug mode enabled?  <?php echo $visionr_DEBUG ? "[ $visionr_DEBUG ]": ''  ?>
            </label>
          </li>
          <li>
            <input type="checkbox" name="visionr_DEBUG" value="true" <?php echo ($visionr_DEBUG ? 'CHECKED' : '') ?> />
          </li>
        </ul>
        <p>
            <input type="submit" value="Save settings" class="button-primary"/>
        </p>
    </form>

    <?php
}

////////////////////////////////////////////////////////////////////////////////

/* CART CUSTOMIZATION */
/*  display metadata where appropriate */
function pechatar_output_local_storage() {
    global $product;
    global $PET_DEBUG;

    $baseURL = home_url();

    $ishide = $PET_DEBUG ? 'text' : 'hidden';
    $lvarser = '{}';

    ?>
    <script>
      window.addEventListener("message", receiveMessage, false);

      function receiveMessage(event) {
        console.info('template configured');
        if (event.origin.indexOf('<?php echo $baseURL ?>') < 0)
          return;

       var butel = document.getElementsByClassName('single_add_to_cart_button')[0];
       if (butel) butel.disabled = false;

       document.getElementById('item-config').value = event.data;
      }
    </script>
    <div class="pechatar-field">
        <input type="<?php echo $ishide ?>" id="item-config" name="item-config" value="<?php echo $lvarser ?>">
    </div>
    <?php
}

add_action('woocommerce_before_add_to_cart_button', 'pechatar_output_local_storage', 10 );

function pechatar_tweak_button() {
  ?>
  <script>
    jQuery(document).ready(function() {
      jQuery('button.single_add_to_cart_button').on('click', function(event) {
          let confval = document.getElementById('item-config').value ;
          if (confval === '' || confval === '{}') {
            alert('please configure your design first');
            event.stopPropagation();
            return false;
          }
          return true;
      })
    })
  </script>
  <?php

}

add_action('woocommerce_after_add_to_cart_button', 'pechatar_tweak_button', 10 );

function pechatar_add_meta_to_cart_item( $cart_item_data, $product_id, $variation_id ) {
    write_log('PECHATAR: initial setup of cart item metadata for ['.$cart_item_key);
    $item_meta = filter_input( INPUT_POST, 'item-config' );
    $cart_item_data['pechatar-meta'] = $item_meta;
    return $cart_item_data;

  }
add_filter( 'woocommerce_add_cart_item_data', 'pechatar_add_meta_to_cart_item', 10, 3 );

function pechatar_add_downloadable($html, $item, $args) {
  // return "<pre> ".var_dump(json_decode($item, true))." </pre>".$html;
  $vimgdata = json_decode($item['pechatar-meta'], true);
  $uuid = $vimgdata["preview_uuid"];

  return "<div class='pechatar-download'><a style='font-size:16pt; color: #e22' href='/documents/{$uuid}.uuid.pdf'> download here </a></h3>";
}

add_filter( 'woocommerce_display_item_meta', 'pechatar_add_downloadable',10, 3);

function custom_checkout_create_order_line_item( $item, $cart_item_key, $values, $order ) {
    write_log('PECHATAR: copy cart item ['.$cart_item_key.'] meta data to order');

    if(isset($values['pechatar-meta'])) {
        $item->update_meta_data( 'pechatar-meta', $values['pechatar-meta'] );
    }
}

add_action( 'woocommerce_checkout_create_order_line_item', 'custom_checkout_create_order_line_item', 20, 4 );

////////////////////////////////////////////////////////////////////////////////

function remove_tabs($tabs) {
  return [];
}

add_filter('woocommerce_product_tabs', 'remove_tabs', 10);

////////////////////////////////////////////////////////////////////////////////

/* CART CUSTOMIZATION */
/*  display metadata with cart contents */

function display_meta( $item_data, $cart_item ) {
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

add_filter( 'woocommerce_get_item_data', 'display_meta', 10, 2 );

/*  display image preview */

function product_cart_image( $_product_img, $cart_item, $cart_item_key ) {
  write_log('PECHATAR: output ['.$cart_item_key.'] thumbnail link');
  $vimgdata = json_decode($cart_item['pechatar-meta'], true);
  $uuid = $vimgdata["preview_uuid"];
  $thumb = 'no thumb';

  if ($uuid) {
    $baseURL = home_url();
    $thumb      =   '<img src="'.$baseURL.'/tmp/documents/'.$uuid.'.uuid.png?operation=resizeImage.png&width=640&height=480" />';
  }

  return $thumb;
}

add_filter( 'woocommerce_cart_item_thumbnail', 'product_cart_image', 10, 3 );

////////////////////////////////////////////////////////////////////////////////

function pechatar_admin_order_thumbnails($item_id, $item, $product ) {
  $vimgdata = json_decode($item['pechatar-meta'], true);
  $uuid = $vimgdata["preview_uuid"];
  if ($uuid) {
    $thumb  = '<img src="'.$baseURL.'/tmp/documents/'.$uuid.'.uuid.png?operation=resizeImage.png&width=320&height=200" />';
    $down   = "<div class='pechatar-download'><a style='font-size:16pt; color: #e22' href='/documents/{$uuid}.uuid.pdf'> download here </a></h3>";
    echo $down."<br/>".$thumb;
  }
}

add_action( 'woocommerce_after_order_itemmeta', 'pechatar_admin_order_thumbnails', 10, 3);

////////////////////////////////////////////////////////////////////////////////

function setup_theme_admin_menus() {
    add_submenu_page('themes.php',
        'Pechatarnik Specific Configuration', 'Pechatarnik Specific', 'manage_options',
        'pechatarnik-specific-configuration', 'pechatarnik_specific_config');
}

add_action("admin_menu", "setup_theme_admin_menus");

?>
