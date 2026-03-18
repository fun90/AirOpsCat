import { Modal } from '/static/tabler/js/tabler.esm.min.js';
import { DataTable } from '/static/js/common/data-table.js';

const backupTable = new DataTable({
    data: {
        entityName: 'backups',
        modalIdPrefix: 'backup-',
        runningBackup: false,
        uploadingBackup: false,
        restoringBackup: false,
        restoreModal: null,
        stats: {
            total: 0,
            totalSize: 0,
            backupDir: '',
            lastBackupTime: null
        }
    },
    methods: {
        getApiUrl() {
            return '/api/admin/backups';
        },

        getDeleteUrl(item) {
            return `/api/admin/backups/${encodeURIComponent(item.fileName)}`;
        },

        getDownloadUrl(item) {
            return `/api/admin/backups/${encodeURIComponent(item.fileName)}/download`;
        },

        triggerUpload() {
            const uploadInput = document.getElementById('backup-upload-input');
            if (uploadInput) {
                uploadInput.click();
            }
        },

        uploadBackupFile(event) {
            const file = event.target.files?.[0];
            if (!file) {
                return;
            }

            const formData = new FormData();
            formData.append('file', file);

            this.uploadingBackup = true;
            fetch('/api/admin/backups/upload', {
                method: 'POST',
                body: formData
            })
                .then(async response => {
                    if (!response.ok) {
                        let message = '上传备份失败';
                        try {
                            const errorData = await response.json();
                            message = errorData.message || message;
                        } catch (_) {
                        }
                        throw new Error(message);
                    }
                    return response.json();
                })
                .then(() => {
                    this.fetchRecords();
                    ToastUtils.show('Success', '备份文件上传成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '上传备份失败', 'danger');
                })
                .finally(() => {
                    this.uploadingBackup = false;
                    event.target.value = '';
                });
        },

        openRestoreModal(item) {
            this.selectedItem = item;
            this.restoreModal = new Modal(document.getElementById('backup-restoreModal'));
            this.restoreModal.show();
        },

        restoreSelectedBackup() {
            if (!this.selectedItem) {
                return;
            }

            this.restoringBackup = true;
            fetch(`/api/admin/backups/${encodeURIComponent(this.selectedItem.fileName)}/restore`, {
                method: 'POST'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('恢复备份失败');
                    }
                    return response.json();
                })
                .then(() => {
                    if (this.restoreModal) {
                        this.restoreModal.hide();
                    }
                    ToastUtils.show('Success', '数据恢复已完成，请刷新并检查系统数据', 'success', 5000);
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '恢复备份失败', 'danger');
                })
                .finally(() => {
                    this.restoringBackup = false;
                });
        },

        runBackup() {
            this.runningBackup = true;
            fetch('/api/admin/backups/run', {
                method: 'POST'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('执行备份失败');
                    }
                    return response.json();
                })
                .then(() => {
                    this.fetchRecords();
                    ToastUtils.show('Success', '数据备份已完成', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '执行备份失败', 'danger');
                })
                .finally(() => {
                    this.runningBackup = false;
                });
        }
    }
});

backupTable.createApp('#app');
