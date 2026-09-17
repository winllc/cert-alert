/* The projects list, and creating one. */
(function (window, $) {
    'use strict';

    function row(project) {
        return '<tr data-project-id="' + CertAlert.escapeHtml(project.id) + '">'
            + '<td><a class="fw-medium" href="/projects/' + project.id + '">'
            + CertAlert.escapeHtml(project.name) + '</a></td>'
            + '<td>' + CertAlert.text(project.description) + '</td>'
            + '<td class="mono text-center">' + project.members + '</td>'
            + '<td class="mono text-center">' + project.servers + '</td>'
            + '<td class="text-secondary">' + CertAlert.escapeHtml(CertAlert.formatDate(project.createdAt) || '')
            + '</td>'
            + '<td class="text-end"><button type="button" class="btn btn-sm project-delete">Delete</button></td>'
            + '</tr>';
    }

    function message(text, isError) {
        $('#project-message').text(text || '')
            .toggleClass('text-danger', !!isError)
            .toggleClass('text-secondary', !isError);
    }

    function problem(xhr, fallback) {
        var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
        return detail || fallback;
    }

    function load() {
        $.getJSON('/api/v1/projects')
            .done(function (projects) {
                var $body = $('#projects-body');
                if (projects.length === 0) {
                    $body.html('<tr><td colspan="6" class="text-secondary">'
                        + 'No projects yet. A project is what a group of people and servers is for - the '
                        + 'directory has no way to say that.</td></tr>');
                    return;
                }
                $body.html(projects.map(row).join(''));
                $body.find('.project-delete').on('click', function () {
                    var $row = $(this).closest('tr');
                    var id = $row.data('project-id');
                    // Deleting the grouping, not the entries in it, so there is nothing to
                    // lose beyond the grouping itself.
                    $.ajax({url: '/api/v1/projects/' + id, type: 'DELETE'})
                        .done(load)
                        .fail(function (xhr) {
                            if (CertAlert.handleUnauthorized(xhr)) {
                                return;
                            }
                            message(problem(xhr, 'Could not delete that project.'), true);
                        });
                });
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $('#projects-body').html('<tr><td colspan="6" class="text-secondary">'
                    + 'Could not load the projects.</td></tr>');
            });
    }

    $(function () {
        if ($('#projects-body').length === 0) {
            return;
        }
        load();

        $('#project-create').on('click', function () {
            var name = $('#project-name').val().trim();
            if (!name) {
                message('A project needs a name.', true);
                return;
            }
            message('');
            $.ajax({
                url: '/api/v1/projects',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({name: name, description: $('#project-description').val().trim() || null})
            })
                .done(function () {
                    $('#project-name').val('');
                    $('#project-description').val('');
                    load();
                })
                .fail(function (xhr) {
                    if (CertAlert.handleUnauthorized(xhr)) {
                        return;
                    }
                    message(problem(xhr, 'Could not create that project.'), true);
                });
        });
    });
})(window, jQuery);
