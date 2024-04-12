/* define i18n messages */
vr.defineI18n(require('./web2print.i18n.json'));

/* define module */
vr.defineModule('web2print',{
	//sortId : 1000
	alias : 'w2p',
	objectdefs : [
		require("./schema/abstract_content"),
		require("./schema/print_template"),
		require("./schema/category_content")
	],
	version : '1.0001'
});

/* final setup */
require("./data/access");
require("./data/languages.active");

