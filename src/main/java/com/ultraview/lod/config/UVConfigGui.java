package com.ultraview.lod.config;

import com.ultraview.lod.UltraViewLOD;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"rawtypes", "unchecked"})
public class UVConfigGui extends GuiConfig {
    public UVConfigGui(GuiScreen parent) {
        super(parent, elements(), UltraViewLOD.MODID, false, false, "UltraView LOD");
    }

    private static List<IConfigElement> elements() {
        List<IConfigElement> list = new ArrayList<IConfigElement>();
        for (String cat : LODConfig.CATEGORIES) list.add(new ConfigElement(LODConfig.config.getCategory(cat)));
        return list;
    }
}
