#!/data/data/com.termux/files/usr/bin/bash
# new-feature-branch.sh
#
# Maakt (of hergebruikt) een feature-branch, commit alle wijzigingen
# en pusht naar GitHub — in één stap, vanuit Termux.
#
# Gebruik:
#   ./new-feature-branch.sh <branch-naam> "<commit boodschap>"
#
# Voorbeeld:
#   ./new-feature-branch.sh feature/jev-pem-scoring "Jev PEM-scoring skeleton"
#
# Uitgangspunt: je staat al in de juiste repo-map (bv. hr_ble_bridge of HBmonitor)
# wanneer je dit script draait.

set -e  # stop direct bij een fout, i.p.v. doorgaan met een kapotte git-state

BRANCH="$1"
MSG="$2"

if [ -z "$BRANCH" ] || [ -z "$MSG" ]; then
  echo "Gebruik: ./new-feature-branch.sh <branch-naam> \"<commit boodschap>\""
  exit 1
fi

echo "== Repo-status controleren =="
git status --short

echo "== main bijwerken =="
git checkout main
git pull

echo "== Branch aanmaken of wisselen naar: $BRANCH =="
if git show-ref --quiet "refs/heads/$BRANCH"; then
  echo "Branch bestaat al lokaal, wissel ernaartoe."
  git checkout "$BRANCH"
else
  git checkout -b "$BRANCH"
fi

echo "== Wijzigingen toevoegen =="
git add .

echo "== Committen =="
# Als er niets te committen is, laat dit de rest van het script niet stuk maken
git commit -m "$MSG" || echo "Niets te committen — ga door naar push."

echo "== Pushen naar GitHub =="
git push -u origin "$BRANCH"

echo ""
echo "Klaar. Branch '$BRANCH' staat op GitHub."
echo "Ga naar de Actions-pagina van de repo, kies deze branch in de 'Run workflow'-dropdown"
echo "om er een test-APK van te bouwen, of maak een Pull Request als je klaar bent om te mergen."
