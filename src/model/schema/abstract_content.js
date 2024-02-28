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
		}
	},
	'varchar_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : 'varchar.i18n.obligatory'
		}
	},
	'text_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : 'text.i18n.obligatory'
		}
	},
	'integer_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'integer.obligatory',
				col : 'ival_int'
			}
		}
	},	
	'double_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'double.obligatory',
				col : 'ival_dbl'
			}
		}
	},	
	'date_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'date.obligatory',
				col : 'ival_dat',
				format : 'date'
			}
		}
	},
	'datetime_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'date.obligatory',
				col : 'ival_dat',
				format : 'datetime'
			}
		}
	},
	'time_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'time.obligatory',
				col : 'ival_dat',
				format : 'time'
			}
		}
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
		}
	},
	'qrcode_content' : {
		inherits : 'web2print.abstract_content',
		properties : {
			'initial_value' : {
				template : 'varchar.obligatory',
				col : 'ival_var'
			} 
		}
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
			'table_data' : 'text.readonly'
		}
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
			'available_colors' : 'text'
		}
	}
}
	