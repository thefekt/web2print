/* LANGUAGE SETUP */

const forceActive = [
	"bg-BG"
];
const forceInactive = [
	"en-GB"
];

/* deactivate */
for (let lc of forceInactive) 
	vr.defineObject({
		SCHEMA : 'core.lang',
		code : lc,
		values : {
			is_active:false
		}
	});
	
/* activate */
for (let lc of forceActive) 
	vr.defineObject({
		SCHEMA : 'core.lang',
		code : lc,
		values : {
			is_active:true
		}
	});
