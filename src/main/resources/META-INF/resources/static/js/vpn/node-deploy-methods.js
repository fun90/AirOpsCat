import { Modal } from '/static/tabler/js/tabler.esm.min.js';

export function createNodeDeployMethods() {
    return {
        openDeploymentHistory(node) {
            if (!node || !node.id) {
                return;
            }

            this.deploymentHistoryNode = node;
            this.deploymentVersions = [];
            this.deploymentVersionDetail = null;
            this.loadingDeploymentVersions = true;
            this.deploymentHistoryModal = new Modal(document.getElementById('node-deploymentHistoryModal'));
            this.deploymentHistoryModal.show();

            fetch(`/api/admin/nodes/${node.id}/deployment-versions`)
                .then(async response => {
                    if (!response.ok) {
                        const error = await response.json().catch(() => ({ message: '获取部署历史失败' }));
                        throw new Error(error.message || '获取部署历史失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.deploymentVersions = Array.isArray(data) ? data : [];
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '获取部署历史失败', 'danger');
                })
                .finally(() => {
                    this.loadingDeploymentVersions = false;
                });
        },

        viewDeploymentVersion(version) {
            if (!this.deploymentHistoryNode || !version) {
                return;
            }

            this.loadingDeploymentVersionDetail = true;
            this.deploymentVersionDetail = null;
            fetch(`/api/admin/nodes/${this.deploymentHistoryNode.id}/deployment-versions/${version}`)
                .then(async response => {
                    if (!response.ok) {
                        const error = await response.json().catch(() => ({ message: '获取版本详情失败' }));
                        throw new Error(error.message || '获取版本详情失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.deploymentVersionDetail = data;
                    this.deploymentVersionDetailModal = new Modal(document.getElementById('node-deploymentVersionDetailModal'));
                    this.deploymentVersionDetailModal.show();
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '获取版本详情失败', 'danger');
                })
                .finally(() => {
                    this.loadingDeploymentVersionDetail = false;
                });
        },

        restoreDeploymentVersion() {
            if (!this.deploymentHistoryNode || !this.deploymentVersionDetail) {
                return;
            }

            this.restoringDeploymentVersion = true;
            fetch(`/api/admin/nodes/${this.deploymentHistoryNode.id}/deployment-versions/${this.deploymentVersionDetail.version}/restore`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    redeploy: false
                })
            })
                .then(async response => {
                    if (!response.ok) {
                        const error = await response.json().catch(() => ({ message: '还原部署版本失败' }));
                        throw new Error(error.message || '还原部署版本失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.fetchRecords();
                    if (this.deploymentVersionDetailModal) {
                        this.deploymentVersionDetailModal.hide();
                    }
                    ToastUtils.show('Success', data.message || '版本还原成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '还原部署版本失败', 'danger');
                })
                .finally(() => {
                    this.restoringDeploymentVersion = false;
                });
        },

        getDeploymentVersionTypeLabel(type) {
            return this.getOptionLabel(this.nodeTypes, type);
        },

        getTagNameById(tagId) {
            const tag = this.availableTags.find(item => String(item.id) === String(tagId));
            return tag ? tag.name : `#${tagId}`;
        },

        getDeploymentVersionSnapshot() {
            return this.deploymentVersionDetail && this.deploymentVersionDetail.snapshot
                ? this.deploymentVersionDetail.snapshot
                : {};
        },

        openBatchDeployModal() {
            this.batchDeployModal = new Modal(document.getElementById('batchDeployModal'));
            this.batchDeployModal.show();
        },

        openBatchTagModal() {
            if (!this.selectedNodeIds.length) {
                ToastUtils.show('Warning', '请先勾选要调整标签的节点', 'warning');
                return;
            }

            const selectedNodes = this.records.filter(node => this.selectedNodeIds.includes(node.id));
            const normalizedTagSets = selectedNodes.map(node => {
                const tags = Array.isArray(node.tags) ? node.tags : Array.from(node.tags || []);
                return tags
                    .map(tag => Number(tag.id))
                    .filter(tagId => !Number.isNaN(tagId))
                    .sort((left, right) => left - right);
            });
            const hasSameTagSet = normalizedTagSets.every(tagIds =>
                JSON.stringify(tagIds) === JSON.stringify(normalizedTagSets[0] || [])
            );
            this.batchTagForm.mode = 'REPLACE';
            this.batchTagForm.tagIds = hasSameTagSet ? [...(normalizedTagSets[0] || [])] : [];
            this.batchTagModal = new Modal(document.getElementById('node-batchTagModal'));
            this.batchTagModal.show();
        },

        getBatchTagModeDescription() {
            if (this.batchTagForm.mode === 'APPEND') {
                return '保存后会在所选节点现有标签的基础上新增下面勾选的标签；已有标签会保留，实际变更的节点会被标记为未部署。';
            }
            return '保存后会将所选节点的标签整体替换为下面勾选的结果，并将这些节点标记为未部署。';
        },

        getBatchTagModeActionLabel() {
            return this.batchTagForm.mode === 'APPEND' ? '新增' : '覆盖';
        },

        openCoreSwitchModal() {
            if (!this.selectedNodeIds.length) {
                ToastUtils.show('Warning', '请先勾选要切换的节点', 'warning');
                return;
            }

            const selectedNodes = this.records.filter(node => this.selectedNodeIds.includes(node.id));
            const hasSingBox = selectedNodes.some(node => node.coreType === 'sing-box');
            this.coreSwitchTarget = 'sing-box';
            const unsupportedNodes = selectedNodes.filter(node =>
                !this.getAvailableProtocols({ type: node.type, coreType: this.coreSwitchTarget })
                    .some(protocol => protocol.value === node.protocol)
            );
            if (unsupportedNodes.length > 0) {
                const labels = unsupportedNodes
                    .map(node => `${node.name || `节点#${node.id}`}(${node.protocol})`)
                    .join('、');
                ToastUtils.show('Warning', `以下节点协议不支持切换到 ${this.coreSwitchTarget}: ${labels}`, 'warning');
                return;
            }
            this.coreSwitchRedeploy = true;
            this.coreSwitchModal = new Modal(document.getElementById('node-coreSwitchModal'));
            this.coreSwitchModal.show();
        },

        isNodeSelected(nodeId) {
            return this.selectedNodeIds.includes(nodeId);
        },

        toggleNodeSelection(nodeId, checked) {
            if (checked) {
                if (!this.selectedNodeIds.includes(nodeId)) {
                    this.selectedNodeIds.push(nodeId);
                }
                return;
            }
            this.selectedNodeIds = this.selectedNodeIds.filter(id => id !== nodeId);
        },

        isAllCurrentPageSelected() {
            return this.records.length > 0 && this.records.every(node => this.selectedNodeIds.includes(node.id));
        },

        toggleSelectAllCurrentPage(checked) {
            if (checked) {
                const merged = new Set([...this.selectedNodeIds, ...this.records.map(node => node.id)]);
                this.selectedNodeIds = Array.from(merged);
                return;
            }
            const currentIds = new Set(this.records.map(node => node.id));
            this.selectedNodeIds = this.selectedNodeIds.filter(id => !currentIds.has(id));
        },

        switchSelectedNodesCore() {
            if (!this.selectedNodeIds.length) {
                ToastUtils.show('Warning', '请先勾选要切换的节点', 'warning');
                return;
            }

            this.switchingCore = true;
            fetch('/api/admin/nodes/switch-core', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    nodeIds: this.selectedNodeIds,
                    targetCoreType: this.coreSwitchTarget,
                    redeploy: this.coreSwitchRedeploy
                })
            })
                .then(async response => {
                    if (!response.ok) {
                        const error = await response.json().catch(() => ({ message: '切换内核失败' }));
                        throw new Error(error.message || '切换内核失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.switchingCore = false;
                    this.coreSwitchModal.hide();
                    this.selectedNodeIds = [];
                    this.fetchRecords();

                    const deploymentSummary = data.redeployed
                        ? `，重部署 ${data.deploymentResults ? data.deploymentResults.filter(result => result.success).length : 0} 项`
                        : '';
                    ToastUtils.show(
                        'Success',
                        `已切换 ${data.switchedCount} 个节点，跳过 ${data.unchangedCount} 个节点${deploymentSummary}`,
                        'success'
                    );
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.switchingCore = false;
                    ToastUtils.show('Error', error.message || '切换内核失败', 'danger');
                });
        },

        submitBatchTagUpdate() {
            if (!this.selectedNodeIds.length) {
                ToastUtils.show('Warning', '请先勾选要调整标签的节点', 'warning');
                return;
            }

            this.batchTagUpdating = true;
            fetch('/api/admin/nodes/batch-tags', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    nodeIds: this.selectedNodeIds,
                    tagIds: this.batchTagForm.tagIds,
                    mode: this.batchTagForm.mode
                })
            })
                .then(async response => {
                    if (!response.ok) {
                        const error = await response.json().catch(() => ({ message: '批量调整标签失败' }));
                        throw new Error(error.message || '批量调整标签失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.batchTagUpdating = false;
                    if (this.batchTagModal) {
                        this.batchTagModal.hide();
                    }
                    this.fetchRecords();
                    ToastUtils.show(
                        'Success',
                        `已${this.getBatchTagModeActionLabel()} ${data.updatedCount || 0} 个节点标签，跳过 ${data.unchangedCount || 0} 个节点`,
                        'success'
                    );
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.batchTagUpdating = false;
                    ToastUtils.show('Error', error.message || '批量调整标签失败', 'danger');
                });
        },

        deploySelectedNodes() {
            this.batchDeploying = true;

            fetch('/api/admin/nodes/deploy-batch', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: '[]'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('批量部署失败');
                    }
                    return response.json();
                })
                .then(data => {
                    const successCount = data.filter(result => result.success).length;
                    const failureCount = data.length - successCount;

                    this.batchDeploying = false;
                    this.batchDeployModal.hide();

                    if (failureCount === 0) {
                        ToastUtils.show('Success', `成功部署 ${successCount} 个节点`, 'success');
                    } else {
                        ToastUtils.show('Warning', `成功: ${successCount}, 失败: ${failureCount}`, 'warning');
                    }

                    this.fetchRecords();
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.batchDeploying = false;
                    ToastUtils.show('Error', '批量部署失败', 'danger');
                });
        },

        deployNode(node, forcibly = false) {
            if (this.deployingNodeIds.includes(node.id)) {
                return;
            }

            this.deployingNodeIds.push(node.id);
            const loadingToast = ToastUtils.loading(
                forcibly ? '重新部署中' : '部署中',
                `${node.name || `节点 #${node.id}`} 正在部署，请稍候...`
            );

            fetch(`/api/admin/nodes/${node.id}/${forcibly ? 'deployForcibly' : 'deploy'}`, {
                method: 'POST'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('部署节点失败');
                    }
                    return response.json();
                })
                .then(data => {
                    if (data.success) {
                        this.fetchRecords();
                        ToastUtils.show('Success', data.message, 'success');
                    } else {
                        ToastUtils.show('Error', data.message, 'danger');
                    }
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '部署节点失败', 'danger');
                })
                .finally(() => {
                    loadingToast.hide();
                    this.deployingNodeIds = this.deployingNodeIds.filter(id => id !== node.id);
                });
        },

        isNodeDeploying(nodeId) {
            return this.deployingNodeIds.includes(nodeId);
        },

        getDeploymentStatusBadgeClass(deployed) {
            return deployed === 1 ? 'bg-success-lt' : 'bg-warning-lt';
        }
    };
}
