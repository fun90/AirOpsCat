/**
 * Common Data Table Component for AirOpsCat
 * 
 * This component provides reusable functionality for data tables including:
 * - Pagination
 * - Filtering and searching
 * - CRUD operations
 * - Modal management
 * 
 * Usage:
 * 1. Import this file in your HTML
 * 2. Create a Vue application and extend from CommonDataTable
 * 3. Override necessary methods and properties for your specific entity
 */

class CommonDataTable {
  constructor(options = {}) {
    // Default data structure that will be extended by specific implementations
    this.data = {
      // Records data
      records: [],
      
      // Search and filter
      searchQuery: '',
      filters: {},
      
      // Pagination
      currentPage: 1,
      pageSize: 10,
      totalItems: 0,
      startIndex: 1,
      endIndex: 1,
      totalPages: 0,
      
      // Selected item and modals
      selectedItem: null,
      deleteModal: null,
      createModal: null,
      editModal: null,
      
      // UI state
      loading: true,
      searchTimeout: null,
      
      // Form data for create/edit operations
      newItem: {},
      editedItem: {},
      
      // Validation
      validationErrors: {}
    };
    
    // Merge provided options
    Object.assign(this.data, options.data || {});
    
    // Methods that will be available in the component
    this.methods = {
      // Lifecycle methods
      mounted() {
        // Initialize data when the component is mounted
        this.fetchRecords();
        
        // Call any additional initialization methods
        if (typeof this.initialize === 'function') {
          this.initialize();
        }
      },
      
      // Data fetching
      fetchRecords() {
        this.loading = true;
        
        // Build query parameters
        const params = new URLSearchParams({
          page: this.currentPage,
          size: this.pageSize
        });
        
        // Add search query if present
        if (this.searchQuery) {
          params.append('search', this.searchQuery);
        }
        
        // Add any other filters
        for (const [key, value] of Object.entries(this.filters)) {
          if (value) {
            params.append(key, value);
          }
        }
        
        // Construct API URL - override this in your specific implementation
        const apiUrl = this.getApiUrl ? 
          this.getApiUrl() : 
          `/api/admin/${this.entityName || 'records'}`;
        
        fetch(`${apiUrl}?${params.toString()}`)
          .then(response => {
            if (!response.ok) {
              throw new Error('Network response was not ok');
            }
            return response.json();
          })
          .then(data => {
            this.records = data.records || [];
            this.totalItems = data.total || 0;
            this.startIndex = (this.currentPage - 1) * this.pageSize + 1;
            this.endIndex = Math.min(this.startIndex + this.pageSize - 1, this.totalItems);
            this.totalPages = data.pages || 0;
            this.currentPage = data.current || 1;
            
            // Update stats if available
            if (data.stats) {
              this.stats = data.stats;
            }
            
            this.loading = false;
            
            // Call after fetch hook if defined
            if (typeof this.afterFetch === 'function') {
              this.afterFetch(data);
            }
          })
          .catch(error => {
            console.error(`Error fetching ${this.entityName || 'records'}:`, error);
            ToastUtils.show('Error', `Failed to load ${this.entityName || 'records'}.`, 'danger');
            this.loading = false;
          });
      },
      
      // Search methods
      searchDebounced() {
        // Clear existing timeout
        if (this.searchTimeout) {
          clearTimeout(this.searchTimeout);
        }
        
        // Set new timeout to delay the API call
        this.searchTimeout = setTimeout(() => {
          this.currentPage = 1; // Reset to first page when searching
          this.fetchRecords();
        }, 500); // Wait for 500ms after user stops typing
      },
      
      // Pagination methods
      prevPage() {
        if (this.currentPage > 1) {
          this.currentPage--;
          this.fetchRecords();
        }
      },
      
      nextPage() {
        if (this.currentPage < this.totalPages) {
          this.currentPage++;
          this.fetchRecords();
        }
      },
      
      goToPage(page) {
        this.currentPage = page;
        this.fetchRecords();
      },
      
      changePageSize() {
        this.currentPage = 1; // Reset to first page when changing page size
        this.fetchRecords();
      },
      
      // // Calculate pagination values
      // get startIndex() {
      //   return (this.currentPage - 1) * this.pageSize + 1;
      // },
      //
      // get endIndex() {
      //   return Math.min(this.startIndex + this.pageSize - 1, this.totalItems);
      // },
      
      get paginationPages() {
        // Show at most 5 page numbers
        const maxPages = 5;
        const pages = [];
        let startPage = Math.max(1, this.currentPage - Math.floor(maxPages / 2));
        let endPage = Math.min(this.totalPages, startPage + maxPages - 1);
        
        if (endPage - startPage + 1 < maxPages) {
          startPage = Math.max(1, endPage - maxPages + 1);
        }
        
        for (let i = startPage; i <= endPage; i++) {
          pages.push(i);
        }
        
        return pages;
      },
      
      // Format utility methods
      formatDate(dateString) {
        if (!dateString) return '-';
        const date = new Date(dateString);
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
      },
      
      formatDateTime(dateString) {
        if (!dateString) return null;
        const date = new Date(dateString);
        return date.toLocaleString();
      },
      
      formatBytes(bytes) {
        bytes = Number(bytes);
        if (isNaN(bytes) || bytes === 0) return '0 B';
        
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return parseFloat((bytes / Math.pow(1024, i)).toFixed(2)) + ' ' + units[i];
      },
      
      copyToClipboard(text) {
        navigator.clipboard.writeText(text)
          .then(() => {
            ToastUtils.show('Success', '已复制到剪贴板', 'success');
          })
          .catch(err => {
            console.error('Failed to copy text: ', err);
            ToastUtils.show('Error', '复制失败', 'danger');
          });
      },
      
      // CRUD Operations
      confirmDelete(item) {
        this.selectedItem = item;
        this.deleteModal = new bootstrap.Modal(document.getElementById(`${this.modalIdPrefix || ''}deleteModal`));
        this.deleteModal.show();
      },
      
      deleteItem() {
        if (!this.selectedItem) return;
        
        const apiUrl = this.getDeleteUrl ? 
          this.getDeleteUrl(this.selectedItem) : 
          `/api/admin/${this.entityName || 'records'}/${this.selectedItem.id}`;
        
        fetch(apiUrl, {
          method: 'DELETE'
        })
          .then(response => {
            if (!response.ok) {
              throw new Error('删除失败');
            }
            
            // Refresh the data
            this.fetchRecords();
            ToastUtils.show('Success', '删除成功', 'success');
            
            // Hide modal
            this.deleteModal.hide();
          })
          .catch(error => {
            console.error('Error:', error);
            ToastUtils.show('Error', '删除失败', 'danger');
          });
      },
      
      openCreateModal() {
        // Reset form - override resetCreateForm in your implementation
        if (typeof this.resetCreateForm === 'function') {
          this.resetCreateForm();
        } else {
          this.newItem = {};
        }
        
        this.validationErrors = {};
        
        // Show modal
        this.createModal = new bootstrap.Modal(document.getElementById(`${this.modalIdPrefix || ''}createModal`));
        this.createModal.show();
      },
      
      createItem() {
        // Validate form - override validateForm in your implementation
        if (typeof this.validateCreateForm === 'function' && !this.validateCreateForm()) {
          return;
        }
        
        // Prepare data for API - override prepareCreateData in your implementation
        const itemData = typeof this.prepareCreateData === 'function' ? 
          this.prepareCreateData() : 
          this.newItem;
        
        const apiUrl = this.getCreateUrl ? 
          this.getCreateUrl() : 
          `/api/admin/${this.entityName || 'records'}`;
        
        fetch(apiUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(itemData)
        })
          .then(response => {
            if (!response.ok) {
              if (response.status === 400) {
                return response.json().then(data => {
                  throw new Error(data.message || 'Validation error');
                });
              }
              throw new Error('响应失败');
            }
            return response.json();
          })
          .then(data => {
            this.fetchRecords(); // Refresh the list
            this.createModal.hide();
            ToastUtils.show('Success', '创建成功', 'success');
            
            // Call after create hook if defined
            if (typeof this.afterCreate === 'function') {
              this.afterCreate(data);
            }
          })
          .catch(error => {
            console.error('Error:', error);
            ToastUtils.show('Error', error.message || '创建失败', 'danger');
          });
      },
      
      openEditModal(item) {
        // Clone item data to avoid direct mutation
        if (typeof this.prepareEditForm === 'function') {
          this.editedItem = this.prepareEditForm(item);
        } else {
          this.editedItem = { ...item };
        }
        
        this.validationErrors = {};
        
        // Show modal
        this.editModal = new bootstrap.Modal(document.getElementById(`${this.modalIdPrefix || ''}editModal`));
        this.editModal.show();
      },
      
      updateItem() {
        // Validate form - override validateForm in your implementation
        if (typeof this.validateEditForm === 'function' && !this.validateEditForm()) {
          return;
        }
        
        // Prepare data for API - override prepareUpdateData in your implementation
        const itemData = typeof this.prepareUpdateData === 'function' ? 
          this.prepareUpdateData() : 
          this.editedItem;
        
        const apiUrl = this.getUpdateUrl ? 
          this.getUpdateUrl(this.editedItem) : 
          `/api/admin/${this.entityName || 'records'}/${this.editedItem.id}`;
        
        fetch(apiUrl, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(itemData)
        })
          .then(response => {
            if (!response.ok) {
              if (response.status === 400) {
                return response.json().then(data => {
                  throw new Error(data.message || 'Validation error');
                });
              }
              throw new Error('响应失败');
            }
            return response.json();
          })
          .then(data => {
            // Update item in the local array if needed
            if (typeof this.updateLocalItem === 'function') {
              this.updateLocalItem(data);
            } else {
              const index = this.records.findIndex(r => r.id === this.editedItem.id);
              if (index !== -1) {
                this.records[index] = data;
              }
            }
            
            this.editModal.hide();
            ToastUtils.show('Success', '更新成功', 'success');
            
            // Call after update hook if defined
            if (typeof this.afterUpdate === 'function') {
              this.afterUpdate(data);
            }
          })
          .catch(error => {
            console.error('Error:', error);
            ToastUtils.show('Error', error.message || '更新失败', 'danger');
          });
      },
      
      // Toggle item status (enable/disable)
      toggleItemStatus(item, disabled) {
        const action = disabled ? 'disable' : 'enable';
        
        const apiUrl = this.getToggleStatusUrl ? 
          this.getToggleStatusUrl(item, action) : 
          `/api/admin/${this.entityName || 'records'}/${item.id}/${action}`;
        
        fetch(apiUrl, {
          method: 'PATCH'
        })
          .then(response => {
            if (!response.ok) {
              throw new Error('更新状态失败');
            }
            return response.json();
          })
          .then(data => {
            // Update item status in the local array
            if (typeof this.updateItemStatus === 'function') {
              this.updateItemStatus(item, data);
            } else {
              const index = this.records.findIndex(r => r.id === item.id);
              if (index !== -1) {
                this.records[index].disabled = data.disabled;
              }
            }
            
            ToastUtils.show('Success', '更新状态成功', 'success');
          })
          .catch(error => {
            console.error('Error:', error);
            ToastUtils.show('Error', '更新状态发生错误', 'danger');
          });
      },
      
      // Generate a random UUID
      uuidv4() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
          var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
          return v.toString(16);
        });
      },
      
      // Generate a random string for passwords, etc.
      generateRandomString(length = 16) {
        const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        let result = "";
        for (let i = 0; i < length; i++) {
          result += charset.charAt(Math.floor(Math.random() * charset.length));
        }
        return result;
      }
    };
    
    // Merge provided methods
    Object.assign(this.methods, options.methods || {});
    
    // Computed properties
    this.computed = {
      ...options.computed
    };
  }
  
  // Create the Vue application
  createApp(selector) {
    return PetiteVue.createApp({
      ...this.data,
      ...this.methods,
      ...this.computed
    }).mount(selector);
  }
}

// Make it available globally
window.CommonDataTable = CommonDataTable;