module.exports = {
	'category_content' : {
		properties : {
			'contents' : {
				template : 'relation',
				parent : 'web2print.abstract_content.category'
			},
			'print_template' : {
				template : 'relation',
				related : 'web2print.print_template'
			}
		},
		icon : 'category'	
	}
}