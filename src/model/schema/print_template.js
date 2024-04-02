const events = require("./print_template.events");

module.exports = {
	'print_template' : {
		properties : {
			'width_mm' : 'double.readonly',
			'height_mm' : 'double.readonly',
			'document' : {
				template : 'relation.file_upload',
				events : {
					'insert,update,delete,updateRelation' : events.onDocumentChange
				}
			},
			'contents' : {
				template : 'relation',
				parent : 'web2print.abstract_content.print_template',
				events : {
					'insert,update,delete' : events.onContentsChange // ONLY UPDATE TODO CHECK ! MULTIPLE > ONLY UPDATE EVENT !! TODO TODO TODO 
				}
			},
			'preview_document' : {
				template : 'relation.file_upload',
				readonly  : true
			},			
			'preview_key' : 'text.readonly'			
		},
		events : {
			insert : events.onInsertobject,
			delete : events.onDeleteObject
		},
		forms : require.resolve("./print_template.forms") /* RESOLVE path */
	}
}