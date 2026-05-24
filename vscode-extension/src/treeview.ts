// v0.43.0 — Projects TreeView for the activity-bar sidebar.

import * as vscode from 'vscode';
import { api, getJson, ProjectDto, BuildDto } from './api';

export class ProjectsTreeProvider implements vscode.TreeDataProvider<ProjectTreeItem> {
  private _onDidChange = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChange.event;

  refresh(): void {
    this._onDidChange.fire();
  }

  getTreeItem(element: ProjectTreeItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: ProjectTreeItem): Promise<ProjectTreeItem[]> {
    if (!element) {
      const list = await getJson<ProjectDto[]>('/api/projects');
      if (!list) {
        return [
          new ProjectTreeItem('(no token — run Login)', '', 'message', vscode.TreeItemCollapsibleState.None),
        ];
      }
      if (list.length === 0) {
        return [
          new ProjectTreeItem('(no projects yet)', '', 'message', vscode.TreeItemCollapsibleState.None),
        ];
      }
      return list.map((p) => {
        const item = new ProjectTreeItem(
          p.name,
          p.id,
          'project',
          vscode.TreeItemCollapsibleState.Collapsed,
        );
        item.description = p.id;
        item.tooltip = `${p.name}\npackage: ${p.packageName}\nlast: ${p.lastBuildStatus ?? '-'}`;
        return item;
      });
    }
    if (element.kind === 'project') {
      // Fetch recent builds for this project.
      const list = await getJson<BuildDto[]>(`/api/projects/${encodeURIComponent(element.projectId)}/builds`);
      if (!list || list.length === 0) {
        return [
          new ProjectTreeItem('(no builds)', element.projectId, 'message', vscode.TreeItemCollapsibleState.None),
        ];
      }
      return list.slice(0, 20).map((b) => {
        const item = new ProjectTreeItem(
          `${b.status} · ${b.id.substring(0, 8)}`,
          element.projectId,
          'build',
          vscode.TreeItemCollapsibleState.None,
        );
        item.description = b.startedAt ?? '';
        item.tooltip = `build ${b.id}\nvariant: ${b.variant}\nstatus: ${b.status}`;
        return item;
      });
    }
    return [];
  }
}

export class ProjectTreeItem extends vscode.TreeItem {
  constructor(
    label: string,
    public readonly projectId: string,
    public readonly kind: 'project' | 'build' | 'message',
    collapsibleState: vscode.TreeItemCollapsibleState,
  ) {
    super(label, collapsibleState);
    this.contextValue = kind;
    if (kind === 'project') this.iconPath = new vscode.ThemeIcon('repo');
    if (kind === 'build') this.iconPath = new vscode.ThemeIcon('tools');
    if (kind === 'message') this.iconPath = new vscode.ThemeIcon('info');
  }
}
