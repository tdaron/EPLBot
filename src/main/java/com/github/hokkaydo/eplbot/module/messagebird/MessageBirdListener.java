package com.github.hokkaydo.eplbot.module.messagebird;

import com.github.hokkaydo.eplbot.Main;
import com.github.hokkaydo.eplbot.MessageUtil;
import com.github.hokkaydo.eplbot.configuration.Config;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class MessageBirdListener extends ListenerAdapter {

    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(4);
    private static final Random RANDOM = new Random();
    private static final String[][] LOG_MESSAGES = {
            {"No message today", "<"},
            {"Message today!", ">="}
    };

    private final Long guildId;
    private final List<ScheduledFuture<?>> dayLoops = new ArrayList<>();
    private final List<ScheduledFuture<?>> perfectTimeLoops = new ArrayList<>();
    private boolean waitingForAnswer;
    private final String type;
    private final Path messagesPath;
    private final List<String> messages = new ArrayList<>();

    public MessageBirdListener(Long guildId, String type) {
        this.guildId = guildId;
        this.type = type;
        this.messagesPath = Path.of(STR."\{Main.PERSISTENCE_DIR_PATH}/\{type.toLowerCase()}_messages.json");
    }

    public void start() {
        reloadMessages();
        long startSeconds = Config.getGuildVariable(guildId, STR."\{type}_BIRD_RANGE_START_DAY_SECONDS");
        long endSeconds = Config.getGuildVariable(guildId, STR."\{type}_BIRD_RANGE_END_DAY_SECONDS");

        long currentSeconds = LocalTime.now().getLong(ChronoField.SECOND_OF_DAY);
        long deltaStart = startSeconds - currentSeconds;
        if (deltaStart <= 0) {
            deltaStart += 24 * 60 * 60;
        }
        Main.LOGGER.log(Level.INFO, "[{0}Bird] Trying to send in {1} seconds", new Object[]{type, deltaStart});
        dayLoops.add(EXECUTOR.schedule(() -> {
            int rnd = RANDOM.nextInt(100);
            int proba = Config.<Integer>getGuildVariable(guildId, STR."\{type}_BIRD_MESSAGE_PROBABILITY");
            String[] logs = LOG_MESSAGES[rnd > proba ? 0 : 1];
            Main.LOGGER.log(Level.INFO, "[%sBird] %s (%d %s %d)".formatted(type, logs[0], proba, logs[1], rnd));
            if (rnd > proba) {
                perfectTimeLoops.removeIf(f -> f.isDone() || f.isCancelled());
                dayLoops.removeIf(f -> f.isDone() || f.isCancelled());
                start();
                return;
            }
            long waitTime = RANDOM.nextLong(endSeconds - startSeconds);
            Main.LOGGER.log(Level.INFO, "[%sBird] Wait %d seconds before sending".formatted(type, waitTime));
            perfectTimeLoops.add(EXECUTOR.schedule(
                    () -> Optional.ofNullable(Main.getJDA().getGuildById(guildId))
                                  .map(guild -> guild.getTextChannelById(Config.getGuildVariable(guildId, STR."\{type}_BIRD_CHANNEL_ID")))
                                  .ifPresentOrElse(
                                          this::sendMessage,
                                          () -> MessageUtil.sendAdminMessage("%s_BIRD_CHANNEL_ID (%s) not found".formatted(type, Config.getGuildVariable(guildId, STR."\{type}_BIRD_CHANNEL_ID")), guildId)
                                  ),
                    waitTime,
                    TimeUnit.SECONDS
            ));
        }, deltaStart, TimeUnit.SECONDS));
    }

    private void sendMessage(TextChannel channel) {
        String nextMessage = Config.getGuildState(guildId, STR."\{type}_BIRD_NEXT_MESSAGE");
        if (nextMessage != null && !nextMessage.isBlank()) {
            channel.sendMessage(nextMessage).setAllowedMentions(null).queue();
            Config.updateValue(guildId, STR."\{type}_BIRD_NEXT_MESSAGE", "");
            this.waitingForAnswer = true;
            perfectTimeLoops.removeIf(f -> f.isDone() || f.isCancelled());
            dayLoops.removeIf(f -> f.isDone() || f.isCancelled());
            start();
            return;
        }
        int randomMessageIndex = RANDOM.nextInt(messages.size());
        channel.sendMessage(messages.get(randomMessageIndex)).queue(ignored -> this.waitingForAnswer = true);
        perfectTimeLoops.removeIf(f -> f.isDone() || f.isCancelled());
        dayLoops.removeIf(f -> f.isDone() || f.isCancelled());
        start();
    }

    public void stop() {
        perfectTimeLoops.forEach(scheduledFuture -> scheduledFuture.cancel(true));
        dayLoops.forEach(scheduledFuture -> scheduledFuture.cancel(true));
        perfectTimeLoops.clear();
        dayLoops.clear();
    }

    public void restart() {
        stop();
        start();
    }

    public void reloadMessages() {
        List<String> tmpMessages = new ArrayList<>();
        try (InputStream is = messagesPath.toUri().toURL().openStream()) {
            JSONArray array = new JSONArray(new JSONTokener(new InputStreamReader(is)));
            for (int i = 0; i < array.length(); i++) {
                tmpMessages.add(array.getString(i));
            }
            this.messages.clear();
            this.messages.addAll(tmpMessages);
        } catch (JSONException e) {
            Main.LOGGER.log(Level.WARNING, "Could not parse JSON file at %s".formatted(messagesPath), e);
        } catch (Exception e) {
            Main.LOGGER.log(Level.WARNING, "Could not read JSON file at %s".formatted(messagesPath), e);
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!waitingForAnswer) return;
        String channelId = Config.getGuildVariable(guildId, STR."\{type}_BIRD_CHANNEL_ID");
        if (!event.getChannel().getId().equals(channelId) || event.isWebhookMessage() || event.getAuthor().isBot() || event.getAuthor().isSystem())
            return;
        this.waitingForAnswer = false;
        String messageBirdRoleId = Config.getGuildVariable(guildId, STR."\{type}_BIRD_ROLE_ID");
        String unicodeEmoji = Config.getGuildVariable(guildId, STR."\{type}_BIRD_UNICODE_REACT_EMOJI");
        Optional.ofNullable(Main.getJDA().getGuildById(guildId)).map(guild -> guild.getRoleById(messageBirdRoleId)).ifPresent(role -> {
            role.getGuild().findMembersWithRoles(role).onSuccess(members -> members.stream().filter(m -> m.getUser().getIdLong() != event.getAuthor().getIdLong()).map(m -> role.getGuild().removeRoleFromMember(m.getUser(), role)).forEach(RestAction::queue));
            role.getGuild().addRoleToMember(event.getAuthor(), role).queue();
            event.getMessage().addReaction(Emoji.fromUnicode(unicodeEmoji)).queue();
        });
    }
}
