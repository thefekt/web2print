<?php

function pechatar_theme_enqueue_styles() {
        // as per https://codex.wordpress.org/Child_Themes
        $parent_style = 'pechatar-style'; // This is 'twentyfifteen-style' for the Twenty Fifteen theme.
    
        wp_enqueue_style( $parent_style, get_template_directory_uri() . '/style.css' );
        wp_enqueue_style( 'child-style',
            get_stylesheet_directory_uri() . '/style.css',
            array( $parent_style ),
            wp_get_theme()->get('Version')
        );
    }

add_action( 'wp_enqueue_scripts', 'pechatar_theme_enqueue_styles' );


/**
 * Opening div for our content wrapper
 */
add_action('woocommerce_before_main_content', 'pechatar_open_div', 5);

function pechatar_open_div() {
    echo '<div class="pechatar-div">';
}

/**
 * Closing div for our content wrapper
 */
add_action('woocommerce_after_main_content', 'pechatar_close_div', 50);

function pechatar_close_div() {
    echo '</div>';
}

?>
