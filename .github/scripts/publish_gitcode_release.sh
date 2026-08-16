#!/usr/bin/env bash

set -euo pipefail

required_variables=(
  GITCODE_TOKEN
  GITCODE_OWNER
  GITCODE_REPOSITORY
  TAG_NAME
  RELEASE_NAME
  RELEASE_ASSETS_DIR
)

for variable in "${required_variables[@]}"; do
  if [[ -z "${!variable:-}" ]]; then
    echo "Required environment variable is not set: $variable" >&2
    exit 1
  fi
done

RELEASE_BODY=${RELEASE_BODY:-}
RELEASE_STATUS=${RELEASE_STATUS:-latest}
if [[ "$RELEASE_STATUS" != "latest" && "$RELEASE_STATUS" != "pre" ]]; then
  echo "RELEASE_STATUS must be 'latest' or 'pre'" >&2
  exit 1
fi

for command in curl git jq timeout; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command is not available: $command" >&2
    exit 1
  fi
done

shopt -s nullglob
apk_files=("$RELEASE_ASSETS_DIR"/*.apk)
if [[ ${#apk_files[@]} -eq 0 ]]; then
  echo "No APK assets found in $RELEASE_ASSETS_DIR" >&2
  exit 1
fi

gitcode_api="https://api.gitcode.com/api/v5/repos/$GITCODE_OWNER/$GITCODE_REPOSITORY"
gitcode_repository="https://gitcode.com/$GITCODE_OWNER/$GITCODE_REPOSITORY.git"
work_dir=$(mktemp -d)

cleanup() {
  git remote remove gitcode-release >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT

cat >"$work_dir/askpass.sh" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  *Username*) printf '%s\n' "$GITCODE_OWNER" ;;
  *Password*) printf '%s\n' "$GITCODE_TOKEN" ;;
  *) exit 1 ;;
esac
EOF
chmod 700 "$work_dir/askpass.sh"

export GIT_ASKPASS="$work_dir/askpass.sh"
export GIT_TERMINAL_PROMPT=0

curl_api_options=(
  --connect-timeout 15
  --max-time 120
  --retry 3
  --retry-delay 5
  --retry-max-time 120
  --retry-all-errors
)
curl_upload_options=(
  --connect-timeout 15
  --max-time 600
  --retry 2
  --retry-delay 5
  --retry-max-time 600
  --retry-all-errors
)

push_to_gitcode() {
  local refspec=$1
  local description=$2
  local status

  echo "$description"
  if timeout --signal=TERM --kill-after=30s 2m \
    git push --progress gitcode-release "$refspec"; then
    return
  else
    status=$?
  fi
  if [[ $status -eq 124 ]]; then
    echo "GitCode push timed out after 2 minutes: $refspec" >&2
  fi
  return "$status"
}

git remote remove gitcode-release >/dev/null 2>&1 || true
git remote add gitcode-release "$gitcode_repository"

# A release only needs a tag that points to a remote commit. Keep the GitCode
# repository binary-only by using an empty root commit instead of pushing HEAD.
tag_exists_status=0
if timeout --signal=TERM --kill-after=30s 2m \
  git ls-remote --exit-code --refs gitcode-release "refs/tags/$TAG_NAME" >/dev/null 2>&1; then
  echo "GitCode tag $TAG_NAME already exists"
else
  tag_exists_status=$?
  if [[ $tag_exists_status -eq 124 ]]; then
    echo "Timed out checking GitCode tag $TAG_NAME" >&2
    exit "$tag_exists_status"
  elif [[ $tag_exists_status -ne 2 ]]; then
    echo "Failed to check GitCode tag $TAG_NAME (exit $tag_exists_status)" >&2
    exit "$tag_exists_status"
  fi

  empty_tree=$(git mktree </dev/null)
  anchor_commit=$(printf 'GitCode release anchor: %s\n' "$TAG_NAME" | \
    env \
      GIT_AUTHOR_NAME='Kototoro Release Bot' \
      GIT_AUTHOR_EMAIL='release-bot@kototoro.app' \
      GIT_COMMITTER_NAME='Kototoro Release Bot' \
      GIT_COMMITTER_EMAIL='release-bot@kototoro.app' \
      git commit-tree "$empty_tree")
  push_to_gitcode \
    "$anchor_commit:refs/tags/$TAG_NAME" \
    "Pushing empty release anchor for $TAG_NAME to GitCode"
fi

encoded_tag=$(jq -rn --arg value "$TAG_NAME" '$value | @uri')
encoded_token=$(jq -rn --arg value "$GITCODE_TOKEN" '$value | @uri')
release_json="$work_dir/release.json"
release_payload="$work_dir/release-payload.json"

jq -n \
  --arg tag_name "$TAG_NAME" \
  --arg name "$RELEASE_NAME" \
  --arg body "$RELEASE_BODY" \
  --arg release_status "$RELEASE_STATUS" \
  '{
    tag_name: $tag_name,
    name: $name,
    body: $body,
    release_status: $release_status
  }' >"$release_payload"

echo "Querying GitCode release $TAG_NAME"
release_status=$(curl "${curl_api_options[@]}" --silent --show-error \
  --output "$release_json" \
  --write-out '%{http_code}' \
  --header "Authorization: Bearer $GITCODE_TOKEN" \
  "$gitcode_api/releases/$encoded_tag?access_token=$encoded_token")

case "$release_status" in
  200)
    jq '{name, body, release_status}' "$release_payload" >"$work_dir/update-payload.json"
    echo "Updating GitCode release $TAG_NAME"
    curl "${curl_api_options[@]}" --fail-with-body --silent --show-error \
      --request PATCH \
      --header "Authorization: Bearer $GITCODE_TOKEN" \
      --header 'Content-Type: application/json' \
      --data-binary "@$work_dir/update-payload.json" \
      --output "$release_json" \
      "$gitcode_api/releases/$encoded_tag?access_token=$encoded_token"
    ;;
  404)
    echo "Creating GitCode release $TAG_NAME"
    curl --connect-timeout 15 --max-time 120 --fail-with-body --silent --show-error \
      --request POST \
      --header "Authorization: Bearer $GITCODE_TOKEN" \
      --header 'Content-Type: application/json' \
      --data-binary "@$release_payload" \
      --output "$release_json" \
      "$gitcode_api/releases?access_token=$encoded_token"
    ;;
  *)
    echo "Failed to query GitCode release (HTTP $release_status)" >&2
    cat "$release_json" >&2
    exit 1
    ;;
esac

for apk in "${apk_files[@]}"; do
  file_name=$(basename "$apk")

  echo "Preparing GitCode release asset $file_name"

  while IFS= read -r asset_id; do
    curl "${curl_api_options[@]}" --fail-with-body --silent --show-error \
      --request DELETE \
      --header "Authorization: Bearer $GITCODE_TOKEN" \
      "$gitcode_api/releases/$encoded_tag/attach_files/$asset_id?access_token=$encoded_token"
  done < <(
    jq -r --arg name "$file_name" '.assets[]? | select(.name == $name and .id != null) | .id' "$release_json"
  )

  upload_json="$work_dir/upload.json"
  encoded_file_name=$(jq -rn --arg value "$file_name" '$value | @uri')
  curl "${curl_api_options[@]}" --fail-with-body --silent --show-error \
    --header "Authorization: Bearer $GITCODE_TOKEN" \
    --output "$upload_json" \
    "$gitcode_api/releases/$encoded_tag/upload_url?access_token=$encoded_token&file_name=$encoded_file_name"

  upload_url=$(jq -er '.url' "$upload_json")
  mapfile -t upload_headers < <(
    jq -r '(.headers // {}) | to_entries[] | "\(.key): \(.value | tostring)"' "$upload_json"
  )
  curl_headers=()
  for header in "${upload_headers[@]}"; do
    curl_headers+=(--header "$header")
  done

  echo "Uploading $file_name to GitCode"
  curl "${curl_upload_options[@]}" --fail-with-body --show-error --progress-bar \
    --request PUT \
    "${curl_headers[@]}" \
    --upload-file "$apk" \
    "$upload_url"
  echo "Uploaded $file_name to GitCode release $TAG_NAME"
done
