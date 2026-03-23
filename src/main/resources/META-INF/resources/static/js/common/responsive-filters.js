function isSameValue(left, right) {
    return left === right;
}

export function createResponsiveFilterMethods(config) {
    const {
        createDefaultFilters,
        getActiveTags,
        applyFilters,
        onReset,
        onClear
    } = config;

    return {
        createDefaultFilters() {
            return createDefaultFilters();
        },

        activeFilterCount() {
            return this.getActiveFilterTags().length;
        },

        hasActiveFilters() {
            return this.activeFilterCount() > 0;
        },

        getActiveFilterTags() {
            return getActiveTags.call(this);
        },

        applyResponsiveFilters() {
            if (typeof applyFilters === 'function') {
                applyFilters.call(this);
                return;
            }

            this.currentPage = 1;
            this.fetchRecords();
        },

        clearFilter(key) {
            const defaults = this.createDefaultFilters();

            if (key === 'search') {
                if (!this.searchQuery) {
                    return;
                }
                this.searchQuery = '';
            } else if (Object.prototype.hasOwnProperty.call(this.filters, key)) {
                if (isSameValue(this.filters[key], defaults[key])) {
                    return;
                }
                this.filters[key] = defaults[key];
            } else {
                return;
            }

            if (typeof onClear === 'function') {
                onClear.call(this, key, defaults);
            }

            this.applyResponsiveFilters();
        },

        resetFilters() {
            const defaults = this.createDefaultFilters();
            const hasChanges = Boolean(this.searchQuery)
                || Object.keys(defaults).some(key => !isSameValue(this.filters[key], defaults[key]));

            this.searchQuery = '';
            this.filters = defaults;

            if (typeof onReset === 'function') {
                onReset.call(this, defaults);
            }

            if (hasChanges) {
                this.applyResponsiveFilters();
            }
        }
    };
}
