/*
 * The license screen: copying the installation id, which a customer pastes into the ENS
 * licensing portal to bind a license to this installation.
 */
(function () {
    'use strict';

    var input = document.getElementById('installation-id');
    var button = document.getElementById('installation-id-copy');
    if (!input || !button) {
        return;
    }
    button.addEventListener('click', function () {
        input.select();
        var done = function () {
            button.textContent = 'Copied';
            setTimeout(function () { button.textContent = 'Copy'; }, 1500);
        };
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(input.value).then(done, function () {
                document.execCommand('copy');
                done();
            });
        } else {
            document.execCommand('copy');
            done();
        }
    });
}());
