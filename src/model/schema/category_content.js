module.exports = {
	'category_content' : {
		properties : {
			'content' : {
				template : 'relation',
				parent : 'web2print.abstract_content.category'
			},
			'parent' : {
				template : 'relation',
				related : 'web2print.category_content'
			},
			'children' : {
				template : 'relation',
				parent : 'web2print.category_content.parent'
			}
		}
	}
}