package com.ultraview.lod.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

import java.util.Set;

public class UVGuiFactory implements IModGuiFactory {
    public void initialize(Minecraft minecraftInstance) { }
    public Class<? extends GuiScreen> mainConfigGuiClass() { return UVConfigGui.class; }
    public Set<IModGuiFactory.RuntimeOptionCategoryElement> runtimeGuiCategories() { return null; }
    public IModGuiFactory.RuntimeOptionGuiHandler getHandlerFor(IModGuiFactory.RuntimeOptionCategoryElement element) { return null; }
}
