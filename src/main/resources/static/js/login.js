/* Login page validation */
function validateLogin() {
    var u = document.f.username.value.trim();
    var p = document.f.password.value;
    if (!u && !p) { alert('Please enter your username and password'); document.f.username.focus(); return false; }
    if (!u)       { alert('Please enter your username'); document.f.username.focus(); return false; }
    if (!p)       { alert('Please enter your password'); document.f.password.focus(); return false; }
    return true;
}

function handleEnterKey(e, type, targetId) {
    if (e.key === 'Enter' || e.keyCode === 13) {
        if (type === 'enter') {
            var el = document.getElementById(targetId);
            if (el) el.click();
        }
    }
}
