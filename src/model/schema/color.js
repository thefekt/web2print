/* abstract def */

module.exports = {
	'color' : {
		properties : {
			'code' : 'code.unique',
			'value_rgb' : 'varchar.color-noalpha.obligatory',
			'value_cmyk' : 'varchar.obligatory'
		},
		icon : 'palette'
	}
}
	