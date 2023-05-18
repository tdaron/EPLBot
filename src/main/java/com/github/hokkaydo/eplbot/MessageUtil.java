package com.github.hokkaydo.eplbot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

public class MessageUtil {

    public static EmbedBuilder toEmbed(Message message) {
        return new EmbedBuilder()
                       .setAuthor(message.getAuthor().getAsTag(), message.getJumpUrl(), message.getAuthor().getAvatarUrl())
                       .appendDescription(message.getContentRaw())
                       .setTimestamp(message.getTimeCreated())
                       .setFooter(message.getGuild().getName() + " - #" + message.getChannel().getName(), message.getGuild().getIconUrl());
    }

    private MessageUtil() {}

    public static void sendAdminMessage(String message, Long guildId) {
        TextChannel adminChannel = Main.getJDA().getChannelById(TextChannel.class, Config.getGuildVariable(guildId, "ADMIN_CHANNEL_ID"));
        if(adminChannel == null) {
            System.err.println("[WARNING] Invalid admin channel");
            return;
        }
        adminChannel.sendMessage(message).queue();
    }

}