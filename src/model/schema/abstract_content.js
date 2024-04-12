/* abstract def */

module.exports = {
	'abstract_content' : {
		abstract : true,
		properties : {
			'objectdef' : 'objectdef.not.readonly',
			'print_template' : {
				template : 'relation',
				related : 'web2print.print_template'
			},
			'dest_page' : 'integer.obligatory.default.0',
			'region' : 'varchar.readonly',
			'category' : {
				template : 'relation',
				related : 'web2print.category_content'
			},
		},
		icon : 'data_object',
		forms : require.resolve("./abstract_content.forms")
	},
	'varchar_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : 'varchar.i18n.obligatory'
		},
		icon : 'text_fields'
	},
	'text_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : 'text.i18n.obligatory'
		},
		icon : 'subject'
	},
	'integer_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'integer.obligatory',
				col : 'ival_int'
			}
		},
		icon : '123'
	},	
	'double_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'double.obligatory',
				col : 'ival_dbl'
			}
		},
		icon : 'percent'
	},	
	'date_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'date.obligatory',
				col : 'ival_dat',
				format : 'date'
			}
		},
		icon : 'calendar_month'
	},
	'datetime_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'date.obligatory',
				col : 'ival_dat',
				format : 'datetime'
			}
		},
		icon : 'today'
	},
	'time_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'time.obligatory',
				col : 'ival_dat',
				format : 'time'
			}
		},
		icon : 'schedule'
	},
	'image_content': {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'relation',
				related : 'documents.file_upload',	
				col : 'ival_rel'		
			},
			'proportion' : {
				template : 'double'
			},
			'documents' : 'documents.file_upload'
		},
		icon : 'photo'
	},
	'qrcode_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'varchar.obligatory',
				col : 'ival_var'
			} 
		},
		icon : 'qr_code'
	},
	'table_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'column_count' : {
				template : 'integer.obligatory',
				min : 1,
				default : 2
			},
			'row_count' : {
				template : 'integer.obligatory',
				min : 1,
				default : 2
			},
			'table_data' : 'text.i18n.readonly'
		},
		icon : 'table_chart'
	},
	'indexed_color_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'varchar.obligatory',
				col : 'ival_var'
			},
			/* TODO NOT WORKING SPECIFY CUSTOM TODO */
			'dest_page' : {
				hidden : true,
				obligatory : false,
			},
			'available_colors' : 'varchar.multiple'
		},
		icon : 'colors'
	}
}
	