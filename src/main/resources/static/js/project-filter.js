/*
 * The project dropdown on the two search tables.
 *
 * Filled from the projects endpoint rather than rendered with the page, so the same markup
 * serves both tables and neither page has to know what projects exist.
 */
(function (window, $) {
    'use strict';

    window.ProjectFilter = {
        /** Fills the dropdown and selects whatever the URL asked for. Returns the element. */
        attach: function (onChange) {
            var select = document.getElementById('project-filter');
            if (!select) {
                return null;
            }
            var wanted = new URLSearchParams(window.location.search).get('projectId');
            $.getJSON('/api/v1/projects')
                .done(function (projects) {
                    projects.forEach(function (project) {
                        var option = document.createElement('option');
                        option.value = project.id;
                        option.textContent = project.name;
                        select.appendChild(option);
                    });
                    if (wanted) {
                        select.value = wanted;
                        if (onChange) {
                            onChange();
                        }
                    }
                });
            return select;
        },

        /** The id currently chosen, or nothing. */
        selected: function () {
            var select = document.getElementById('project-filter');
            return select && select.value ? select.value : null;
        }
    };
})(window, jQuery);
