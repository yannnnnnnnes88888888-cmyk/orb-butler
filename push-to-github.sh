#!/usr/bin/env bash
# 把光球管家上传到你的 GitHub，用 Actions 云端编译出 APK
# 用法：./push-to-github.sh 你的GitHub用户名 仓库名（默认 orb-butler）
set -e
cd "$(dirname "$0")"

USER="${1:?用法: ./push-to-github.sh GitHub用户名 [仓库名]}"
REPO="${2:-orb-butler}"

if ! command -v gh >/dev/null 2>&1; then
  echo "先安装 GitHub CLI: https://cli.github.com/  然后 gh auth login"
  exit 1
fi

git init -q 2>/dev/null || true
git add -A
git commit -qm "光球管家 v1.0：悬浮光球 + 系统通知 + 精确闹钟" || true
git branch -M main

if gh repo view "$USER/$REPO" >/dev/null 2>&1; then
  git remote remove origin 2>/dev/null || true
  git remote add origin "https://github.com/$USER/$REPO.git"
else
  gh repo create "$USER/$REPO" --public --source=. --remote=origin --push || {
    echo "自动创建失败，请在网页上建一个空仓库 $USER/$REPO 后重跑本脚本";
    exit 1;
  }
fi

git push -u origin main
echo ""
echo "✅ 已推送。打开下面地址点 Run workflow，跑完后在 Artifacts 里下载 APK："
echo "   https://github.com/$USER/$REPO/actions"
