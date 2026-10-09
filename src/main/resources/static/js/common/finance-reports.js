/*
 * Finance reports — Trial Balance, Profit & Loss, Balance Sheet, Tax Register, Audit Log and Period Close.
 *
 * EX-2c: moved here unchanged from business.js so every dashboard (business, school, welfare, farm) runs the SAME code
 * over the same markup (fragments/finance.html :: financeSection). Functions stay global: the menus and tabs call them
 * by name. Needs escHtml (dom-safe.js), t (i18n.js via the header), uiConfirm/uiAlert (confirm-dialog.js) — present on
 * every dashboard — and serverContext. It carries its own date helper: dateToYMD lives in main.js, which only the
 * business dashboard loads.
 */
/** yyyy-MM-dd of a local Date (the browser's own calendar day). */
function finYmd(d){
	var m=d.getMonth()+1, day=d.getDate();
	return d.getFullYear()+'-'+(m<10?'0'+m:m)+'-'+(day<10?'0'+day:day);
}

// Dedicated #FinanceDiv view with a report switcher + per-report filter criteria (replaces the old
// modal dialogs). Remembers the last report + filter values in localStorage so reopening lands where
// you left off. Backend contracts: trial-balance/balance-sheet take ?asOf; pnl/tax-register take
// ?from&to; audit takes ?action&limit.
var FIN_REPORTS = {
	trialBalance: { fields:['asOf'],          run:finRunTrialBalance },
	pnl:          { fields:['from','to'],     run:finRunPnl },
	balanceSheet: { fields:['asOf'],          run:finRunBalanceSheet },
	taxRegister:  { fields:['from','to'],     run:finRunTaxRegister },
	auditLog:     { fields:['action','limit'], run:finRunAuditLog },
	periodClose:  { fields:[],                run:finRunPeriodClose }
};
var finCurrent = 'trialBalance';

function finToday(){ return finYmd(new Date()); }
function finMonthStart(){ var d=new Date(); return finYmd(new Date(d.getFullYear(),d.getMonth(),1)); }
function finPrefs(){ try{ return JSON.parse(localStorage.getItem('finPrefs')||'{}'); }catch(e){ return {}; } }
function finSavePrefs(){
	try{ localStorage.setItem('finPrefs', JSON.stringify({
		report:finCurrent, asOf:$('#finAsOf').val(), from:$('#finFrom').val(),
		to:$('#finTo').val(), action:$('#finAction').val(), limit:$('#finLimit').val() })); }catch(e){}
}

// Open the Finance view on a given report — called by the sidebar menu + the in-page tab buttons.
function showFinance(report){
	if(!FIN_REPORTS[report]) report='trialBalance';
	// EX-2c — a report this dashboard does not offer (Tax Register on a school, a remembered choice from elsewhere)
	// opens Profit & Loss instead of an empty view.
	if(!document.querySelector('#finTabs .fin-tab[data-report="'+report+'"]')) report='pnl';
	finCurrent=report;
	$('.formDiv').hide();
	$('#FinanceDiv').show();
	document.querySelectorAll('#finTabs .fin-tab').forEach(function(b){ b.classList.toggle('active', b.getAttribute('data-report')===report); });
	// seed inputs from saved prefs / sensible defaults (only when empty, so a user's edits survive tab switches)
	var saved=finPrefs();
	if(!$('#finAsOf').val()) $('#finAsOf').val(saved.asOf||finToday());
	if(!$('#finFrom').val()) $('#finFrom').val(saved.from||finMonthStart());
	if(!$('#finTo').val())   $('#finTo').val(saved.to||finToday());
	if(saved.action!=null && !$('#finAction').val()) $('#finAction').val(saved.action);
	if(saved.limit && !$('#finLimit').val())         $('#finLimit').val(saved.limit);
	// show only the filters this report uses
	var use=FIN_REPORTS[report].fields;
	[['asOf','#finAsOfWrap'],['from','#finFromWrap'],['to','#finToWrap'],['action','#finActionWrap'],['limit','#finLimitWrap']]
		.forEach(function(f){ $(f[1]).toggle(use.indexOf(f[0])>=0); });
	runFinanceReport();
}

function runFinanceReport(){
	finSavePrefs();
	document.getElementById('FinanceResults').innerHTML='<div style="padding:10px">Loading…</div>';
	FIN_REPORTS[finCurrent].run();
}
function finSet(html){ document.getElementById('FinanceResults').innerHTML=html; }
function finFail(){ finSet('<div style="padding:10px;color:#c0392b">Could not load the report. Check that finance-service is running.</div>'); }
function finSection(title, rows, total){
	var h='<h5 style="font-weight:700;margin:12px 0 4px">'+escHtml(title)+'</h5><table class="table table-condensed" style="width:100%"><tbody>';
	(rows||[]).forEach(function(r){ h+='<tr><td>'+escHtml((r.code?r.code+' ':'')+(r.name||''))+'</td><td class="text-right">'+Number(r.amount||0).toFixed(2)+'</td></tr>'; });
	if(!(rows||[]).length) h+='<tr><td colspan="2" style="color:#777">None.</td></tr>';
	h+='<tr><th class="text-right">Total '+escHtml(title)+'</th><th class="text-right">'+Number(total||0).toFixed(2)+'</th></tr></tbody></table>';
	return h;
}

function finRunTrialBalance(){
	var asOf=$('#finAsOf').val();
	$.get(serverContext+'gl/trialBalance', asOf?{asOf:asOf}:{}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp; var rows=d.rows||[];
		var h='<table class="table table-striped" style="width:100%"><thead><tr><th>Code</th><th>Account</th><th class="text-right">Debit</th><th class="text-right">Credit</th></tr></thead><tbody>';
		rows.forEach(function(r){ h+='<tr><td>'+escHtml(r.code||'')+'</td><td>'+escHtml(r.name||'')+'</td><td class="text-right">'+Number(r.debit||0).toFixed(2)+'</td><td class="text-right">'+Number(r.credit||0).toFixed(2)+'</td></tr>'; });
		if(!rows.length) h+='<tr><td colspan="4" class="text-center" style="color:#777">No ledger entries yet — post a sale or purchase to populate the GL.</td></tr>';
		h+='</tbody><tfoot><tr><th colspan="2" class="text-right">Total</th><th class="text-right">'+Number(d.totalDebit||0).toFixed(2)+'</th><th class="text-right">'+Number(d.totalCredit||0).toFixed(2)+'</th></tr></tfoot></table>';
		h+='<div style="text-align:right;font-weight:700;color:'+(d.balanced?'#0f6e56':'#c0392b')+'">'+(d.balanced?'Balanced ✓':'NOT balanced')+'</div>';
		finSet(h);
	}, 'json').fail(finFail);
}

function finRunPnl(){
	$.get(serverContext+'gl/pnl', {from:$('#finFrom').val(), to:$('#finTo').val()}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var h=finSection('Income', d.income, d.totalIncome)+finSection('Expenses', d.expense, d.totalExpense);
		var np=Number(d.netProfit||0);
		h+='<div style="text-align:right;font-size:16px;font-weight:800;color:'+(np>=0?'#0f6e56':'#c0392b')+'">Net Profit: '+np.toFixed(2)+'</div>';
		finSet(h);
		finAppendPnlTrend();   // AN-1: the last 12 months beneath the period's statement
	}, 'json').fail(finFail);
}

// AN-1 — the last 12 months from analytics-service (finance's P&L month by month). Its own call: analytics down leaves
// the statement above untouched and says so in one line. Stale = the books could not be asked; the months shown are
// the ones stored before, and a month never stored shows a dash, not a zero.
function finAppendPnlTrend(){
	var put=function(html){ if(finCurrent==='pnl') document.getElementById('FinanceResults').insertAdjacentHTML('beforeend', html); };
	$.get(serverContext+'gl/pnlTrend', function(resp){
		var r=(typeof resp==='string')?JSON.parse(resp):resp, d=r && r.data;
		if(!d || !d.months){ put('<div data-cy="pnl-trend-off" style="margin-top:14px;color:#777">The 12-month trend is not available right now.</div>'); return; }
		var f=function(x){ return x==null ? '—' : Number(x).toFixed(2); };
		var h='<div data-cy="pnl-trend" style="margin-top:16px"><h5 style="font-weight:700;margin:0 0 4px">Last 12 months</h5>';
		if(d.stale) h+='<div style="color:#b9770e;margin-bottom:4px">The books could not be reached: these are the figures as last worked out.</div>';
		h+='<table class="table table-striped table-condensed" style="width:100%"><thead><tr><th>Month</th><th class="text-right">Income</th>'
			+'<th class="text-right">Expenses</th><th class="text-right">Net</th></tr></thead><tbody>';
		d.months.slice().reverse().forEach(function(m){
			var n=m.net==null ? null : Number(m.net);
			h+='<tr data-month="'+escHtml(m.month)+'"><td>'+escHtml(m.month)+'</td><td class="text-right">'+f(m.revenue)+'</td>'
				+'<td class="text-right">'+f(m.expenses)+'</td><td class="text-right" style="color:'+(n!=null && n<0?'#c0392b':'inherit')+'">'+f(m.net)+'</td></tr>';
		});
		h+='</tbody><tfoot><tr><th>Total</th><th class="text-right">'+f(d.totalRevenue)+'</th><th class="text-right">'+f(d.totalExpenses)+'</th>'
			+'<th class="text-right">'+f(d.net)+'</th></tr></tfoot></table></div>';
		put(h);
	}, 'json').fail(function(){ put('<div data-cy="pnl-trend-off" style="margin-top:14px;color:#777">The 12-month trend is not available right now.</div>'); });
}

function finRunBalanceSheet(){
	var asOf=$('#finAsOf').val();
	$.get(serverContext+'gl/balanceSheet', asOf?{asOf:asOf}:{}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var h=finSection('Assets', d.assets, d.totalAssets)+finSection('Liabilities', d.liabilities, d.totalLiabilities);
		var eq=(d.equity||[]).slice();
		if(Number(d.netIncome||0)!==0) eq.push({code:'',name:'Net income (current period)',amount:d.netIncome});
		h+=finSection('Equity', eq, d.totalEquity);
		h+='<div style="text-align:right;font-weight:700;color:'+(d.balanced?'#0f6e56':'#c0392b')+'">Assets '+Number(d.totalAssets||0).toFixed(2)+' = Liab + Equity '+(Number(d.totalLiabilities||0)+Number(d.totalEquity||0)).toFixed(2)+(d.balanced?' ✓':' — NOT balanced')+'</div>';
		finSet(h);
	}, 'json').fail(finFail);
}

function finRunTaxRegister(){
	$.get(serverContext+'taxRegister', {from:$('#finFrom').val(), to:$('#finTo').val()}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var f=function(x){return Number(x||0).toFixed(2);};
		var h='<div style="color:#777;margin-bottom:8px">Period: '+escHtml((d.from||'').toString())+' → '+escHtml((d.to||'').toString())+'</div>';
		h+='<table class="table" style="width:100%"><tbody>'
			+'<tr><td>Output tax (sales)</td><td class="text-right">'+f(d.outputTax)+'</td></tr>'
			+'<tr><td>Less adjustments (returns/voids)</td><td class="text-right">-'+f(d.outputAdjusted)+'</td></tr>'
			+'<tr><th>Net output tax</th><th class="text-right">'+f(d.netOutput)+'</th></tr>'
			+'<tr><td>Input tax (purchases)</td><td class="text-right">'+f(d.inputTax)+'</td></tr>'
			+'<tr><td>Less adjustments (purchase returns)</td><td class="text-right">-'+f(d.inputAdjusted)+'</td></tr>'
			+'<tr><th>Net input tax</th><th class="text-right">'+f(d.netInput)+'</th></tr>'
			+'</tbody></table>';
		var np=Number(d.netPayable||0);
		h+='<div style="text-align:right;font-size:16px;font-weight:800;color:'+(np>=0?'#0f6e56':'#c0392b')+'">Net tax payable: '+f(np)+'</div>';
		var lines=d.lines||[];
		if(lines.length){
			h+='<h5 style="font-weight:700;margin:14px 0 4px">Register</h5><table class="table table-striped" style="width:100%"><thead><tr><th>Date</th><th>Source</th><th>Ref</th><th class="text-right">Output (Cr)</th><th class="text-right">Adjust/Input (Dr)</th></tr></thead><tbody>';
			lines.forEach(function(l){
				h+='<tr><td>'+escHtml((l.date||'').toString())+'</td><td>'+escHtml(l.source||'')+'</td><td>'+escHtml(l.ref||'')+'</td>'
					+'<td class="text-right">'+(Number(l.credit||0)?f(l.credit):'')+'</td>'
					+'<td class="text-right">'+(Number(l.debit||0)?f(l.debit):'')+'</td></tr>';
			});
			h+='</tbody></table>';
		}
		finSet(h);
		finAppendTaxBreakdown();   // multi-rate: per-rate breakdown beneath the net-payable summary
	}, 'json').fail(finFail);
}

// Multi-rate tax: append a "taxable + tax by rate" table (from the transactional lines) below the register.
function finAppendTaxBreakdown(){
	$.get(serverContext+'taxBreakdown', {from:$('#finFrom').val(), to:$('#finTo').val()}, function(resp){
		var d=(resp && resp.object) ? resp.object : ((typeof resp==='string')?JSON.parse(resp):resp);
		var rows=(d && d.rows) ? d.rows : [];
		if(!rows.length) return;
		var f=function(x){return Number(x||0).toFixed(2);};
		var h='<h5 style="font-weight:700;margin:16px 0 4px">Breakdown by rate</h5>'
			+'<table class="table table-striped" style="width:100%"><thead><tr><th class="text-right">Rate %</th>'
			+'<th class="text-right">Output taxable</th><th class="text-right">Output tax</th>'
			+'<th class="text-right">Input taxable</th><th class="text-right">Input tax</th>'
			+'<th class="text-right">Net tax</th></tr></thead><tbody>';
		rows.forEach(function(r){
			h+='<tr><td class="text-right">'+Number(r.rate||0)+'</td>'
				+'<td class="text-right">'+f(r.outputTaxable)+'</td><td class="text-right">'+f(r.outputTax)+'</td>'
				+'<td class="text-right">'+f(r.inputTaxable)+'</td><td class="text-right">'+f(r.inputTax)+'</td>'
				+'<td class="text-right">'+f(r.netTax)+'</td></tr>';
		});
		h+='</tbody><tfoot><tr><th class="text-right">Total</th>'
			+'<th class="text-right">'+f(d.totalOutputTaxable)+'</th><th class="text-right">'+f(d.totalOutputTax)+'</th>'
			+'<th class="text-right">'+f(d.totalInputTaxable)+'</th><th class="text-right">'+f(d.totalInputTax)+'</th>'
			+'<th class="text-right">'+f(d.netPayable)+'</th></tr></tfoot></table>';
		document.getElementById('FinanceResults').insertAdjacentHTML('beforeend', h);
	}, 'json');
}

function finRunAuditLog(){
	var q={limit:$('#finLimit').val()||200}; var a=$('#finAction').val(); if(a) q.action=a;
	$.get(serverContext+'getAuditLog', q, function(resp){
		var rows=(typeof resp==='string')?JSON.parse(resp):resp;
		if(!Array.isArray(rows)){
			// Show the reason the SERVER gave. This said "Check that audit-service is running" for every
			// failure, including a permission refusal from a service that was running perfectly — sending
			// the reader to look at the one thing that was not wrong.
			// t() returns the KEY when it is missing, so there is no inline fallback to lean on — the key
			// ships in all six bundles.
			var why = (rows && rows.message) ? rows.message : t('ui.js.auditLoadFailed');
			finSet('<div style="padding:10px;color:#c0392b">' + escHtml(why) + '</div>');
			return;
		}
		if(!rows.length){ finSet('<div style="padding:10px;color:#777">No audit events yet.</div>'); return; }
		/*
		 * The timestamp now arrives with an offset (…+05:00), so it must be FORMATTED rather than
		 * string-patched. The old code did `.replace('T',' ')`, which was fine for a zoneless value and
		 * would now leave the offset dangling on the end of every row — a shopkeeper does not need to read
		 * "+05:00" 200 times to know when a sale happened.
		 *
		 * Rendered in the reader's own locale, which is what they actually want: this is their shop's log.
		 */
		function auditWhen(v) {
			if (!v) return '';
			var d = new Date(String(v));
			if (isNaN(d.getTime())) return String(v);
			return d.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })
				+ ' ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
		}
		var h='<table class="table table-striped" style="width:100%"><thead><tr><th>When</th><th>Action</th><th>Entity</th><th class="text-right">Amount</th><th>User</th><th>Source</th><th>Details</th></tr></thead><tbody>';
		rows.forEach(function(r){
			var entity=escHtml((r.entityType||'')+(r.entityRef?(' '+r.entityRef):''));
			h+='<tr><td>'+escHtml(auditWhen(r.occurredAt))+'</td>'
				+'<td>'+escHtml(r.action||'')+'</td><td>'+entity+'</td>'
				+'<td class="text-right">'+(r.amount!=null?Number(r.amount).toFixed(2):'')+'</td>'
				+'<td>'+escHtml(r.userId!=null?('#'+r.userId):'')+'</td>'
				+'<td>'+escHtml(r.sourceService||'')+'</td>'
				+'<td>'+escHtml(r.details||'')+'</td></tr>';
		});
		h+='</tbody></table>';
		finSet(h);
	}, 'json').fail(finFail);
}

// Period close: read the org's lock state, and (owner/admin) close/reopen. The finance-service is the single
// source of truth; every dated business op (sale/purchase/payment/edit/void) is rejected in a locked period.
function finRunPeriodClose(){
	$.get(serverContext+'gl/periodLock', function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var locked=(d && d.lockedThrough) ? d.lockedThrough : null;
		var h='<div style="max-width:560px">';
		h+='<p style="color:#555">Closing the books through a date locks it: sales, purchases, payments, edits and voids dated on or before it are rejected until you reopen. Transactions dated after the lock are unaffected.</p>';
		h+='<div style="padding:12px;border-radius:6px;margin:10px 0;font-weight:700;background:'+(locked?'#fdecea':'#eafaf1')+';color:'+(locked?'#c0392b':'#0f6e56')+'">'
			+(locked?('Books are CLOSED through '+escHtml(locked)):'Books are OPEN — no period lock.')+'</div>';
		if(window.canClosePeriod){
			h+='<div class="form-group"><label>Lock the books through</label>'
				+'<input type="date" id="finLockDate" class="form-control" style="max-width:220px" value="'+escHtml(locked||finToday())+'"></div>';
			h+='<button class="btn btn-danger" onclick="finSetPeriodLock()">Close period</button> ';
			if(locked) h+='<button class="btn btn-default" onclick="finReopenPeriod()">Reopen (clear lock)</button>';
		}else{
			h+='<div style="color:#777">Only an owner/admin can change the period lock.</div>';
		}
		h+='</div>';
		finSet(h);
	}, 'json').fail(finFail);
}
function finSetPeriodLock(){
	var d=$('#finLockDate').val(); if(!d){ uiAlert({ title:t('ui.js.pickADate'), message:t('ui.js.chooseTheDateToLockTheBooks'), tone:'warning' }); return; }
	uiConfirm({
		title: t('ui.js.closeTheBooksThrough') + d + '?',
		message: t('ui.js.backDatedSalesPurchasesPaymentsEditsAnd'),
		confirmText: t('ui.js.closeTheBooks'),
		tone: 'warning'
	}).then(function(ok){
		if(!ok) return;
		$.post(serverContext+'gl/periodLock', {lockedThrough:d}, function(){ finRunPeriodClose(); })
			.fail(function(){ uiAlert({ title:t('ui.js.couldNotCloseThePeriod'), message:t('ui.js.youMayNotHavePermissionOrFinance'), tone:'danger' }); });
	});
}
function finReopenPeriod(){
	uiConfirm({
		title: t('ui.js.reopenTheBooks'),
		message: t('ui.js.thisClearsThePeriodLockBackDated'),
		confirmText: t('ui.js.reopenPeriod'),
		tone: 'warning'
	}).then(function(ok){
		if(!ok) return;
		$.post(serverContext+'gl/periodLock', {}, function(){ finRunPeriodClose(); })
			.fail(function(){ uiAlert({ title:t('ui.js.couldNotReopen'), message:t('ui.js.thePeriodLockCouldNotBeCleared'), tone:'danger' }); });
	});
}

// Back-compat shims — any old caller (or the sidebar menu) routes into the page view.
function openTrialBalance(){ showFinance('trialBalance'); }
function openPnl(){ showFinance('pnl'); }
function openBalanceSheet(){ showFinance('balanceSheet'); }
function openTaxRegister(){ showFinance('taxRegister'); }
function openAuditLog(){ showFinance('auditLog'); }
