package me.char321.sfadvancements.core.criteria.completer;

import com.balugaq.jeg.api.patches.JEGGuideEntry;
import com.balugaq.jeg.api.patches.JEGGuideHistory;
import com.github.drakescraft_labs.slimefun4.api.player.PlayerProfile;
import com.github.drakescraft_labs.slimefun4.core.guide.GuideHistory;
import me.char321.sfadvancements.SFAdvancements;
import me.char321.sfadvancements.api.criteria.Criterion;
import me.char321.sfadvancements.api.criteria.SearchCriterion;
import me.char321.sfadvancements.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SearchCriterionCompleter implements CriterionCompleter {
    private static boolean jegSupported;

    static {
        try {
            Class.forName("com.balugaq.jeg.api.patches.JEGGuideHistory");
            jegSupported = true;
        } catch (ClassNotFoundException e) {
            jegSupported = false;
        }
    }

    private final Map<String, List<SearchCriterion>> criteria = new HashMap<>();

    /**
     * Ultimo termino ya evaluado por jugador.
     *
     * <p>El temporizador corre cada 10 ticks, de modo que sin este registro se releia la
     * pagina de la guia y se encolaba una tarea de sincronia por jugador cada medio segundo
     * aunque el jugador no hubiera buscado nada; ademas un mismo termino volvia a contar una y
     * otra vez para los criterios que lo usan. Es concurrente porque
     * {@link PlayerProfile#get} entrega su callback en el hilo principal cuando el perfil ya
     * esta cacheado, pero en el hilo de carga cuando todavia no lo esta.
     */
    private final Map<UUID, String> processedSearches = new ConcurrentHashMap<>();

    private final Field queueField;
    private final Method getIndexedObject;

    private boolean reflectionWarned;

    public SearchCriterionCompleter() {
        Field resolvedQueue;
        Method resolvedGetter;
        try {
            resolvedQueue = GuideHistory.class.getDeclaredField("queue");
            resolvedQueue.setAccessible(true);
            resolvedGetter = Class.forName("com.github.drakescraft_labs.slimefun4.core.guide.GuideEntry").getDeclaredMethod("getIndexedObject");
            resolvedGetter.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
            resolvedQueue = null;
            resolvedGetter = null;
        }
        queueField = resolvedQueue;
        getIndexedObject = resolvedGetter;
        if (queueField == null || getIndexedObject == null) {
            return;
        }

        Bukkit.getScheduler().runTaskTimer(SFAdvancements.instance(), () -> {
            // Reposo: sin nadie conectado no hay guia que vigilar.
            if (Bukkit.getPlayerCount() == 0) {
                return;
            }

            forgetOfflinePlayers();

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                UUID id = onlinePlayer.getUniqueId();
                PlayerProfile.get(onlinePlayer, profile -> {
                    String term = lastSearchedTerm(profile);
                    if (term == null) {
                        // Sin busqueda pendiente se olvida la anterior, para que repetir el
                        // mismo texto mas adelante cuente otra vez.
                        processedSearches.remove(id);
                        return;
                    }

                    if (term.equals(processedSearches.put(id, term))) {
                        return;
                    }

                    Utils.runSync(() -> onSearch(onlinePlayer, term));
                });
            }
        }, 10, 10);
    }

    /**
     * Termino de la ultima entrada de la guia del jugador, o {@code null} si no hay ninguna.
     *
     * <p>Un fallo de reflexion ya no se propaga: se avisa una sola vez, porque antes escribia la
     * traza completa cada medio segundo y por jugador.
     */
    private String lastSearchedTerm(PlayerProfile profile) {
        if (jegSupported && profile.getGuideHistory() instanceof JEGGuideHistory jeg) {
            var entry = jeg.getLastEntry(false);
            return entry instanceof JEGGuideEntry.SearchTermEntry searchTermEntry ? searchTermEntry.get() : null;
        }

        if (queueField == null || getIndexedObject == null) {
            return null;
        }

        try {
            Deque<?> queue = (Deque<?>) queueField.get(profile.getGuideHistory());
            if (queue == null || queue.isEmpty()) {
                return null;
            }

            Object str = getIndexedObject.invoke(queue.getLast());
            return str instanceof String search ? search : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportReflectionFailureOnce(profile, e);
            return null;
        }
    }

    private void reportReflectionFailureOnce(PlayerProfile profile, Exception failure) {
        if (reflectionWarned) {
            return;
        }

        reflectionWarned = true;
        SFAdvancements.instance().getLogger().warning(
            "No se pudo leer el historial de la guia de " + profile.getUUID() + ": " + failure
        );
    }

    /**
     * Descarta los registros de los jugadores que ya no estan conectados, para que el mapa no
     * crezca de forma indefinida a lo largo de una sesion larga.
     */
    private void forgetOfflinePlayers() {
        if (processedSearches.size() <= Bukkit.getPlayerCount()) {
            return;
        }

        processedSearches.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void onSearch(Player player, String str) {
        List<SearchCriterion> criteria = this.criteria.get(str);
        if (criteria == null) return;

        for (SearchCriterion criterion : criteria) {
            criterion.perform(player);
        }
    }

    @Override
    public void register(Criterion criterion) {
        if (!(getCriterionClass().isInstance(criterion))) {
            throw new IllegalArgumentException("criterion must be a " + getCriterionClass().getName());
        }

        SearchCriterion criterion1 = (SearchCriterion) criterion;
        criteria.computeIfAbsent(criterion1.getSearch(), k -> new ArrayList<>()).add(criterion1);
    }

    @Override
    public Class<? extends Criterion> getCriterionClass() {
        return SearchCriterion.class;
    }

    @Override
    public void reload() {
        criteria.clear();
    }
}