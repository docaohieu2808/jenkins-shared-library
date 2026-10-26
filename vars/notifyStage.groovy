def call(Map config, Map args = [:]) {
    def status = args.status ?: 'UNKNOWN'
    def channel = config.notifications?.slack
    def webhook = config.notifications?.webhook

    def message = "${status}: *${env.JOB_NAME}* #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"

    // Slack
    if (channel) {
        try {
            def color = status == 'SUCCESS' ? 'good' : 'danger'
            slackSend(channel: channel, color: color, message: message)
        } catch (e) {
            echo "Slack notification failed: ${e.message}"
        }
    }

    // Generic webhook (Discord, Teams, etc.). The JSON goes through a file and the URL through the
    // environment so quotes in a job name cannot break the command.
    if (webhook) {
        try {
            def payload = groovy.json.JsonOutput.toJson([
                text: message,
                content: message  // Discord
            ])
            writeFile file: 'notify-payload.json', text: payload
            withEnv(["NOTIFY_WEBHOOK=${webhook}"]) {
                sh 'curl -fsS -X POST -H "Content-Type: application/json" -d @notify-payload.json "$NOTIFY_WEBHOOK"'
            }
        } catch (e) {
            echo "Webhook notification failed: ${e.message}"
        }
    }

    // Always log
    echo message
}
