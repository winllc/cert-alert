/**
 * Click-to-fill for the demo's sign-in accounts.
 *
 * <p>The credentials are printed on the page whether or not this runs: a visitor can read
 * them and type them, and the whole tile is legible with scripting off. What this saves is
 * the retyping, which matters because being each role in turn is the point - somebody
 * trying all four types the same password four times otherwise.
 *
 * <p>Served only on a demo, so nothing here exists on a real deployment to be pointed at.
 */
(function () {
    'use strict';

    var form = document.querySelector('form[action$="/login"]');
    var username = document.getElementById('username');
    var password = document.getElementById('password');
    if (!form || !username || !password) {
        return;
    }

    document.querySelectorAll('.demo-account').forEach(function (choice) {
        choice.addEventListener('click', function () {
            username.value = choice.getAttribute('data-username') || '';
            password.value = choice.getAttribute('data-password') || '';
            // Marks which one was picked, so a visitor going through the roles in order can
            // see where they are without reading the form back.
            document.querySelectorAll('.demo-account').forEach(function (other) {
                other.classList.toggle('active', other === choice);
            });
            // Straight in: they chose an account rather than a name to type, and stopping
            // to press Sign in adds nothing. Submitting the form rather than clicking the
            // button so the CSRF token goes with it exactly as it would otherwise.
            form.submit();
        });
    });
})();
