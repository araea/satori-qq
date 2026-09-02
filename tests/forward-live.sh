#!/data/data/com.termux/files/usr/bin/bash
# Live merge-forward probe for both delivery paths (native / fake).
#
#   bash tests/forward-live.sh [group_id]
#
# Sends one <message forward> with 3 nodes to the group, then reports:
#   - what the send returned (native_forward / res_id / message_id)
#   - the last messages the GROUP actually received (spam check)
#   - the last messages the bot's own self-chat received (scaffolding check)
#   - whether the card content can be read back via get_forward
set -u
GROUP=${1:-280183116}
TOKEN=satori-qq-token
BASE=http://127.0.0.1:3001/v1
SELF=$(curl -s -m 5 -X POST $BASE/login.get -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' | jq -r '.user.id // empty')
if [ -z "$SELF" ]; then echo "login not ready"; exit 1; fi
STAMP=$(date +%s | tail -c 5)

post() { curl -s -m 60 -X POST "$BASE/$1" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d "$2"; }

NODES=""
for n in A B C; do
  NODES="$NODES<message><author id=\"$SELF\" name=\"nawyjx\"/>fwd-$STAMP-$n</message>"
done
CONTENT="<message forward>$NODES</message>"

echo "== send (group $GROUP, self $SELF) =="
SENT=$(post message.create "$(jq -n --arg ch "$GROUP" --arg c "$CONTENT" \
  '{channel_id: $ch, content: $c}')")
echo "$SENT" | jq -c '.[0] // .'
MSG_ID=$(echo "$SENT" | jq -r '.[0].id // .[0].message_id // empty')
RES_ID=$(echo "$SENT" | jq -r '.[0].res_id // .[0].forward_id // empty')
NATIVE=$(echo "$SENT" | jq -r '.[0].native_forward // false')

sleep 2
echo "== group history (last 6, newest first) =="
post message.list "$(jq -n --arg ch "$GROUP" \
  '{channel_id: $ch, limit: 6, order: "desc", direction: "before"}')" \
  | jq -c '.data[]? | {id: .id, user: .user.id, content: (.content // "")[0:90]}'

echo "== self-chat history private:$SELF (last 6, newest first) =="
post message.list "$(jq -n --arg ch "private:$SELF" \
  '{channel_id: $ch, limit: 6, order: "desc", direction: "before"}')" \
  | jq -c '.data[]? | {id: .id, user: .user.id, content: (.content // "")[0:90]}'

echo "== get_forward =="
if [ -n "$RES_ID" ]; then
  post get_forward "$(jq -n --arg id "$RES_ID" '{id: $id}')" \
    | jq -c '[.data[]? | {user: .user.id, text: ([.content[]? | .data.text] | join(""))}]'
else
  echo "no res_id returned"
fi
echo "== summary: native_forward=$NATIVE msg_id=$MSG_ID res_id=$RES_ID =="
