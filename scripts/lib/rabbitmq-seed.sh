# shellcheck shell=bash
#
# Seeds a RabbitMQ cluster with the states a demo or a screenshot sweep needs, using rabbitmqadmin, which ships in
# the RabbitMQ image. Sourced by demo-seed.sh and qa-seed.sh; needs `say`, `api` (lib/signin.sh) and $COMPOSE, the
# docker compose invocation of the stack. Bash 3.2: no mapfile, no associative arrays, no ${x,,}.
#
# The stack has either the demo's three nodes (rabbitmq-1 to rabbitmq-3) or the dev stack's single `rabbitmq`
# (which also answers to rabbitmq-1), and the seed adapts: the registration lists every node.

# Sets RABBITMQ_MANAGEMENT and RABBITMQ_AMQP (JSON lists, as Rahdar reaches the nodes) and RABBITMQ_NODE, the
# service the seed's tools run in.
rabbitmq_init() {
  if $COMPOSE config --services 2>/dev/null | grep -qx rabbitmq-1; then
    RABBITMQ_MANAGEMENT='["http://rabbitmq-1:15672","http://rabbitmq-2:15672","http://rabbitmq-3:15672"]'
    RABBITMQ_AMQP='["amqp://rabbitmq-1:5672","amqp://rabbitmq-2:5672","amqp://rabbitmq-3:5672"]'
    RABBITMQ_NODE=rabbitmq-1
  else
    RABBITMQ_MANAGEMENT='["http://rabbitmq:15672"]'
    RABBITMQ_AMQP='["amqp://rabbitmq:5672"]'
    RABBITMQ_NODE=rabbitmq
  fi
}

rabbitmqadmin() { # args…, run in the first node against its own management API
  $COMPOSE exec -T -e RABBITMQADMIN_NON_INTERACTIVE_MODE=true "$RABBITMQ_NODE" \
    rabbitmqadmin -q --base-uri http://localhost:15672 -u rahdar -p rahdar "$@" >/dev/null \
    || echo "  rabbitmqadmin $*: failed" >&2
}

# Registers the cluster as "rabbit" through Rahdar's API, replacing a previous registration of that name.
rabbitmq_register() {
  local existing
  existing=$(api GET "/clusters?size=500" | python3 -c 'import json,sys; print(next((c["id"] for c in json.load(sys.stdin)["data"] if c["name"]=="rabbit"), ""))')
  if [ -n "$existing" ]; then
    echo "removing the previous registration: $existing"
    api DELETE "/clusters/$existing" >/dev/null
  fi
  rabbitmq_cluster=$(api POST /clusters -d "{
    \"system\": \"rabbitmq\",
    \"name\": \"rabbit\",
    \"description\": \"RabbitMQ — three queue types, a policy, a shovel and an exchange nothing is bound to\",
    \"connection\": {
      \"system\": \"rabbitmq\",
      \"managementUrls\": $RABBITMQ_MANAGEMENT,
      \"amqpEndpoints\": $RABBITMQ_AMQP,
      \"credentials\": {\"username\": \"rahdar\", \"password\": \"rahdar\"}
    }
  }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  echo "registered: $rabbitmq_cluster"
}

rabbitmq_publish() { # vhost exchange routing-key count: one exec, many messages
  $COMPOSE exec -T "$RABBITMQ_NODE" sh -c "i=0; while [ \$i -lt $4 ]; do \
    rabbitmqadmin -q --base-uri http://localhost:15672 -u rahdar -p rahdar -V '$1' publish message -e '$2' -k '$3' \
      -m \"{\\\"seq\\\":\$i}\" >/dev/null 2>&1 || exit 1; i=\$((i+1)); done" \
    || echo "  publishing to $2 in $1: failed" >&2
}

# Virtual hosts, exchanges, the three queue types, a policy, permissions, a shovel and the messages.
rabbitmq_seed_data() {
  say "preparing RabbitMQ: virtual hosts, exchanges, queues, a policy, a shovel"
  rabbitmqadmin declare vhost --name payments
  rabbitmqadmin declare permissions -V payments --username rahdar --configure '.*' --write '.*' --read '.*'
  # An account that may only read in payments, so the permissions page has something less than everything.
  rabbitmqadmin declare user --name payments-reader --password payments-reader --tags ''
  rabbitmqadmin declare permissions -V payments --username payments-reader --configure '^$' --write '^$' --read '^payments\.'

  rabbitmqadmin declare exchange --name orders --type topic
  rabbitmqadmin declare exchange --name audit --type fanout
  # Nothing is bound to this one: a message published to it is not routed.
  rabbitmqadmin declare exchange --name orders.unrouted-test --type direct
  rabbitmqadmin declare queue --name orders.eu --type classic --durable true
  rabbitmqadmin declare queue --name orders.us --type quorum --durable true
  rabbitmqadmin declare queue --name audit-log --type stream --durable true
  rabbitmqadmin declare binding --source orders --destination-type queue --destination orders.eu --routing-key 'eu.#'
  rabbitmqadmin declare binding --source orders --destination-type queue --destination orders.us --routing-key 'us.#'
  rabbitmqadmin declare binding --source audit --destination-type queue --destination audit-log --routing-key ''

  rabbitmqadmin declare exchange -V payments --name payments --type direct
  rabbitmqadmin declare queue -V payments --name payments.settlement --type quorum --durable true
  rabbitmqadmin declare binding -V payments --source payments --destination-type queue \
    --destination payments.settlement --routing-key settle

  rabbitmqadmin declare policy --name orders-eu-limits --pattern '^orders\.eu$' --apply-to queues \
    --priority 1 --definition '{"max-length":10000,"message-ttl":86400000}'
  say "publishing messages"
  # orders.us has no consumer, so its backlog stays; the stream gets its 200 records through the audit exchange.
  rabbitmq_publish / orders eu.new 40
  rabbitmq_publish / orders us.new 120
  rabbitmq_publish / audit '' 200
  rabbitmq_publish payments payments settle 25
  # Declared last, so that what it copies from here on does not add to the stream's 200 records.
  # A dynamic shovel that copies every new order to the audit exchange, and so to the stream.
  rabbitmqadmin shovels declare_amqp091 --name orders-to-audit \
    --source-uri 'amqp://rahdar:rahdar@localhost:5672' --source-exchange orders --source-exchange-routing-key '#' \
    --destination-uri 'amqp://rahdar:rahdar@localhost:5672' --destination-exchange audit
}

# What only a sweep wants: a name built to break a layout. A 200-character queue name.
rabbitmq_seed_long() {
  local name
  name="qa.$(printf 'queue-segment-%02d.' $(seq 1 11))terminal-q"
  say "building a long RabbitMQ name"
  rabbitmqadmin declare queue --name "$name" --type quorum --durable true
}
