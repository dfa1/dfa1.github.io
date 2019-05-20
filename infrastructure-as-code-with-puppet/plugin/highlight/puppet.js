function puppet(hljs) {
    var STRING = {
    	className: 'string',
    	variants: [
      		{ begin: /'/, end: /'/},
      		{ begin: /"/, end: /"/},
		]
	};
	var COMMENT = {
    	className: 'comment',
    	variants: [
      		{ begin: '#', end: '$', },
		]
	};
	return {
		keywords: {
			keyword:  'node define class include',
			built_in: 'file service package exec mount tidy cron host user group ssh_authorized_key File Service Package',
			literal: 'true false present absent directory running latest installed stopped',
		},
		contains: [
			STRING,
			COMMENT,
	        hljs.NUMBER_MODE,
		]
	};
}
