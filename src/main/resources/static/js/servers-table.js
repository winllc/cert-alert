/* The servers search table. */
(function ($) {
    'use strict';

    $(function () {
        var certState = document.getElementById('cert-state');
        var withinDays = document.getElementById('within-days');
        var pocInput = document.getElementById('poc-email');
        var pocUserField = document.getElementById('poc-user-field');
        var pocUserLabel = document.getElementById('poc-user-label');
        var pocUserClear = document.getElementById('poc-user-clear');
        var applied = document.getElementById('applied-filters');

        // Arriving from a user row filters by that person. The id travels rather than the
        // address, because the server resolves it to every value a serverPOC could use to
        // name them - the FSD schema says that attribute carries a name, not an address.
        var params = new URLSearchParams(window.location.search);
        var pocUserId = params.get('pocUserId');
        if (pocUserId) {
            pocUserLabel.textContent = params.get('pocName') || ('user #' + pocUserId);
            pocUserField.hidden = false;
        }
        var legacyPoc = params.get('poc') || params.get('pocEmail');
        if (legacyPoc) {
            pocInput.value = legacyPoc;
        }

        function readFilters() {
            var filters = {};
            switch (certState.value) {
                case 'expired':
                    filters.expired = 'true';
                    break;
                case 'not-expired':
                    filters.expired = 'false';
                    break;
                case 'expiring-soon':
                    filters.certificateStatus = 'EXPIRING_SOON';
                    break;
                case 'valid':
                    filters.certificateStatus = 'VALID';
                    break;
                case 'none':
                    filters.hasCertificates = 'false';
                    break;
                default:
                    break;
            }
            var days = withinDays.value.trim();
            if (days) {
                filters.expiringWithinDays = days;
            }
            if (pocUserId) {
                filters.pocUserId = pocUserId;
            }
            var contact = pocInput.value.trim();
            if (contact) {
                filters.poc = contact;
            }
            return filters;
        }

        var table = CertAlert.initTable({
            selector: '#servers-table',
            endpoint: '/api/v1/datatables/servers',
            readFilters: readFilters,
            filterInputs: [certState, withinDays, pocInput],
            order: [[1, 'asc']],
            detailUrl: function (row) {
                return '/api/v1/servers/' + row.id + '/certificates';
            },
            onFiltersApplied: function (filters) {
                applied.innerHTML = CertAlert.describeFilters(filters);
            },
            columns: [
                {data: 'id', orderable: false, searchable: false, className: 'expand',
                    render: function () { return '+'; }},
                {data: 'commonName', render: renderText},
                {data: 'serverUrl', className: 'mono', render: renderText},
                {data: 'icServerAddress', className: 'mono', render: renderText},
                {data: 'serverPocDisplay', render: renderText},
                {data: 'lifeCycleStatus', render: renderText},
                {data: 'atoStatus', render: renderText},
                {data: 'dutyOrganization', render: renderText},
                {data: 'certificateCount', searchable: false, className: 'mono'},
                {data: 'certificateStatus', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.statusBadge(value) : value;
                }},
                {data: 'earliestExpiry', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.expiryCell(value) : value;
                }},
                {data: 'lastSyncedAt', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(CertAlert.formatDate(value)) : value;
                }},
                {data: 'dn', className: 'mono', render: renderText}
            ]
        });

        pocUserClear.addEventListener('click', function () {
            pocUserId = null;
            pocUserField.hidden = true;
            CertAlert.reload(table, readFilters());
        });

        function renderText(value, type) {
            return type === 'display' ? CertAlert.text(value) : value;
        }
    });
})(jQuery);
