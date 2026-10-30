package com.ultraview.lod.command;

import com.ultraview.lod.UltraViewLOD;
import com.ultraview.lod.config.LODConfig;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

public class UVCommand extends CommandBase {
    public String getCommandName() { return "uvlod"; }
    public String getCommandUsage(ICommandSender s) { return "/uvlod <status|toggle|debug|distance <blocos>|clear|reset>"; }
    public int getRequiredPermissionLevel() { return 0; }
    public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

    private void say(ICommandSender s, String m) { s.addChatMessage(new ChatComponentText("[UltraView LOD] " + m)); }

    public void processCommand(ICommandSender s, String[] a) throws CommandException {
        String cmd = a.length > 0 ? a[0].toLowerCase() : "status";
        if (cmd.equals("status")) {
            for (String l : UltraViewLOD.manager.debugLines()) say(s, l);
        } else if (cmd.equals("toggle")) {
            LODConfig.config.get(LODConfig.GENERAL, "enabled", true).set(!LODConfig.enabled);
            LODConfig.sync();
            say(s, LODConfig.enabled ? "ativado" : "desativado");
        } else if (cmd.equals("debug")) {
            LODConfig.config.get(LODConfig.DEBUG, "debugOverlay", false).set(!LODConfig.debugOverlay);
            LODConfig.sync();
            say(s, "overlay de diagnostico: " + (LODConfig.debugOverlay ? "ligado" : "desligado"));
        } else if (cmd.equals("distance") && a.length > 1) {
            try {
                int d = Integer.parseInt(a[1]);
                LODConfig.config.get(LODConfig.DISTANCE, "visualDistance", 1000).set(Math.max(128, Math.min(8000, d)));
                LODConfig.sync();
                say(s, "distancia visual: " + LODConfig.visualDistance + " blocos");
            } catch (NumberFormatException e) { say(s, "numero invalido"); }
        } else if (cmd.equals("clear")) {
            say(s, "cache deste mundo limpo (" + UltraViewLOD.manager.clearCache() + " arquivos removidos)");
        } else if (cmd.equals("reset")) {
            LODConfig.restoreDefaults();
            say(s, "configuracoes restauradas");
        } else {
            say(s, getCommandUsage(s));
        }
    }
}
