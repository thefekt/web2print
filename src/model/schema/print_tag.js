module.exports = {
	'print_tag' : {
		properties : {
			'category' : {
				template : 'relation',
				related : 'web2print.tag_category'
			},
			'templates' : {
				template : 'relation.multiple',
				related : 'web2print.print_template'
			}
		}
	}
}