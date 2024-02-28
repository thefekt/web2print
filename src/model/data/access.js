/* INITIAL ACCOUNTS */

vr.defineObject({
	SCHEMA : 'core.user_role',
	code : 'users:boss',
	values : {
		parent_groups : [{
			SCHEMA : 'core.user_role',
			code : 'customer:admin'
		},{
			SCHEMA : 'core.user_role',
			code : 'documents:admin'
		}]
	}
});

vr.defineObject({
	SCHEMA : 'core.user',
	code : 'boss',
	values : {
		user_group : {
			SCHEMA : 'core.user_role',
			code : 'users:boss'
		},
		person : {
			SCHEMA : 'contacts.employee',
			code : 'BOSS001',
			values : {
				name : 'Boss',
				first_name : 'Ca$h',
				email : 'boss@web2print.demo'	
			}
		},
		is_enabled : true,
		password : '2DA4CB70$TyJOeMRoX2vbxI7w/Op88/155sk='
	}
});



/* hide /visionr documents.folder */
vr.defineObject({
	SCHEMA : 'documents.folder',
	code : 'visionr',
	cond : "path='/visionr'",
	values : {
		access_read : 'administrators'
	}
});
