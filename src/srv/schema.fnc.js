exports.commonHideIfNew = function(details) {
    if (details.transaction != "insert")
        return false;
    return true; 
}  

exports.hideIfNoDocument = function(details) {
    if (exports.commonHideIfNew(details)) 
    	return true;
    var object = details.object;
    if (!object) 
    	return true;
    return !object.document;
}