package io.github.sudoitir.rahdar.kernel.plugin.internal.validation;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerSystem;
import io.github.sudoitir.rahdar.kernel.plugin.CalVer;
import io.github.sudoitir.rahdar.kernel.plugin.Contract;
import io.github.sudoitir.rahdar.kernel.plugin.RahdarVersion;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorException;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Inspects an uploaded plugin jar before anything is stored or run (design.md §3,
 * specs/plugin-runtime). No plugin code is ever executed or linked: the jar is opened with
 * {@link JarFile}, and bytecode is read with the JDK's {@code java.lang.classfile} API. The same
 * validator runs again before every activation.
 *
 * <p>Every violation names what is wrong and what the author must change; the caller decides
 * what to do with a {@link ValidationReport} that is not {@linkplain ValidationReport#valid()
 * valid}.
 */
@Component
public class PluginValidator {

    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    static final long MAX_READ_BYTES = 250L * 1024 * 1024;
    static final int MAX_ENTRIES = 20_000;
    static final long MAX_RATIO = 100;

    private static final String JAR_UNREADABLE = "jar-unreadable";
    private static final String REUPLOAD = "Re-upload the jar.";
    private static final String DECLARE_PERMISSION =
            "Declare that permission under permissions, or name one that is declared.";

    private static final Pattern METRIC_NAME = Pattern.compile("[a-z0-9-]+:[a-z][a-z0-9_.]{0,63}");
    private static final String RESOURCE_TYPES_CODE = "permission-resource-types";
    private static final String SCOPE_RESOURCE = "resource";
    private static final String MCP_TOOL_RESOURCE = "mcp-tool-resource";
    private static final Set<String> SCOPES = Set.of("global", "cluster", SCOPE_RESOURCE);
    private static final Set<String> RESOURCE_TYPES = Set.of("subscription", "destination");
    private static final Set<String> METRIC_UNITS = Set.of("count", "per_second", "ms", "ratio");
    private static final Set<String> DENIED_MANIFEST_ATTRIBUTES =
            Set.of("Class-Path", "Launcher-Agent-Class", "Add-Opens", "Add-Exports", "Enable-Native-Access");

    /**
     * Packages no plugin may claim as, or nest its {@code basePackage} under: Rahdar's, the JDK's and
     * the frameworks', the broker SDK's, and every installed broker driver's own client libraries.
     */
    private static final List<String> RESERVED_PREFIXES = reservedPrefixes();

    private static List<String> reservedPrefixes() {
        List<String> reserved = new ArrayList<>(List.of(
                "io.github.sudoitir.rahdar",
                "java.",
                "javax.",
                "jakarta.",
                "org.springframework.",
                "com.fasterxml.",
                "tools.jackson.",
                "org.hibernate.",
                "liquibase.",
                "io.github.sudoitir.openbroker."));
        try (BrokerDrivers drivers = BrokerDrivers.load()) {
            for (BrokerSystem system : drivers.systems()) {
                system.nativePackages().forEach(p -> reserved.add(p.endsWith(".") ? p : p + "."));
            }
        }
        return List.copyOf(reserved);
    }

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)++$");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*+(?:\\.[a-z][a-z0-9_]*+)*+$");

    private final PluginDescriptorParser descriptorParser;
    private final RahdarVersion rahdarVersion;

    public PluginValidator(PluginDescriptorParser descriptorParser, RahdarVersion rahdarVersion) {
        this.descriptorParser = descriptorParser;
        this.rahdarVersion = rahdarVersion;
    }

    /**
     * @param jarFile the jar to inspect, already on local disk (a temp file, not the upload stream)
     * @param otherBasePackages the {@code basePackage} of every other installed plugin, for the
     *     overlap check
     */
    public ValidationReport validate(Path jarFile, Set<String> otherBasePackages) {
        List<Violation> violations = new ArrayList<>();
        if (!sizeAcceptable(jarFile, violations)) {
            return new ValidationReport(null, violations);
        }

        Optional<List<String>> scanned = scanEntries(jarFile, violations);
        if (scanned.isEmpty()) {
            // A structural problem (zip slip, a nested archive, an oversize entry) stops here:
            // nothing past this point can be trusted to describe the jar's real contents.
            return new ValidationReport(null, violations);
        }
        List<String> entryNames = scanned.get();

        try (JarFile jar = new JarFile(jarFile.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
            return validateJar(jar, entryNames, otherBasePackages, violations);
        } catch (IOException e) {
            violations.add(new Violation(JAR_UNREADABLE, "The jar could not be opened: " + e.getMessage(), REUPLOAD));
            return new ValidationReport(null, violations);
        }
    }

    private boolean sizeAcceptable(Path jarFile, List<Violation> violations) {
        long size;
        try {
            size = Files.size(jarFile);
        } catch (IOException e) {
            violations.add(new Violation(JAR_UNREADABLE, "The jar could not be read: " + e.getMessage(), REUPLOAD));
            return false;
        }
        if (size > MAX_FILE_BYTES) {
            violations.add(new Violation(
                    "jar-too-large",
                    "The jar is %d bytes, over the %d byte limit.".formatted(size, MAX_FILE_BYTES),
                    "Shrink the jar — relocate only the libraries you actually use, and shade with minimization."));
            return false;
        }
        return true;
    }

    private ValidationReport validateJar(
            JarFile jar, List<String> entryNames, Set<String> otherBasePackages, List<Violation> violations)
            throws IOException {
        if (!consistent(jar, entryNames, violations)) {
            return new ValidationReport(null, violations);
        }
        int before = violations.size();
        Signer signer = JarSignature.check(jar, entryNames, violations);
        if (violations.size() > before) {
            // A jar whose signature fails cannot be trusted to describe itself.
            return new ValidationReport(null, violations);
        }
        checkManifest(jar, violations);
        PluginDescriptor descriptor = readDescriptor(jar, violations);
        if (descriptor == null) {
            return new ValidationReport(null, violations);
        }
        checkAllowlist(entryNames, descriptor, violations);
        checkDescriptorFields(descriptor, otherBasePackages, violations);
        new BytecodeChecks(descriptor).check(jar, entryNames, violations);
        List<ChangesetInfo> changesets = new LiquibasePreflight(descriptor).check(jar, entryNames, violations);
        return new ValidationReport(descriptor, violations, changesets, signer);
    }

    /**
     * The class loader reads a jar through its central directory; the scan above read its local
     * headers. An archive whose two views disagree (an entry only one of them lists, or a different
     * compressed size) is a zip-confusion payload — what was checked would not be what is loaded —
     * so it is refused. The central directory also supplies the compression ratio for entries the
     * stream could not size.
     */
    private boolean consistent(JarFile jar, List<String> scanned, List<Violation> violations) {
        List<String> central = jar.stream().map(ZipEntry::getName).sorted().toList();
        if (!central.equals(scanned.stream().sorted().toList())) {
            violations.add(new Violation(
                    "jar-inconsistent",
                    "The jar's central directory and its entries do not list the same files.",
                    "Rebuild the jar with a standard tool (Maven or the JDK's jar); do not edit it by hand."));
            return false;
        }
        for (ZipEntry entry : jar.stream().toList()) {
            long compressed = entry.getCompressedSize();
            if (compressed > 0 && entry.getSize() / compressed > MAX_RATIO) {
                violations.add(new Violation(
                        "jar-compression-ratio",
                        "\"%s\" compresses at more than %d:1.".formatted(entry.getName(), MAX_RATIO),
                        "Do not ship highly compressible padding; store the file uncompressed or remove it."));
                return false;
            }
        }
        return true;
    }

    /** What an entry's name gives away (unsafe, duplicate, nested archive), or null when it is fine. */
    private static Violation nameViolation(ZipEntry entry, Set<String> seen) {
        String name = entry.getName();
        if (!isSafeName(name)) {
            return new Violation(
                    "jar-unsafe-entry-name",
                    "\"%s\" is an absolute, backslash or path-traversal entry name.".formatted(name),
                    "Rebuild the jar with a standard zip tool; do not hand-craft entries.");
        }
        if (!seen.add(name)) {
            return new Violation(
                    "jar-duplicate-entry",
                    "\"%s\" appears more than once in the jar.".formatted(name),
                    "Rebuild the jar without duplicate entries.");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!entry.isDirectory() && (lower.endsWith(".jar") || lower.endsWith(".zip"))) {
            return new Violation(
                    "jar-nested-archive",
                    "\"%s\" is a nested jar or zip.".formatted(name),
                    "Shade and relocate dependencies into this jar's own classes instead of bundling nested archives.");
        }
        return null;
    }

    /**
     * A raw, sequential scan for zip-slip and zip-bomb shapes, done before the jar is trusted to
     * {@link JarFile}. Returns the entry names, or empty once a structural violation has
     * been recorded (the caller stops there).
     *
     * <p>ponytail: per-entry compressed size can read as {@code -1} while streaming a deflated
     * entry; the ratio check is then skipped for that one entry rather than guessed at. The total
     * 250&nbsp;MB read cap still bounds the damage.
     */
    private Optional<List<String>> scanEntries(Path jarFile, List<Violation> violations) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long totalRead = 0;
        int count = 0;
        try (var in = Files.newInputStream(jarFile);
                ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    violations.add(new Violation(
                            "jar-too-many-entries",
                            "The jar has more than %d entries.".formatted(MAX_ENTRIES),
                            "Remove unused resources from the jar."));
                    return Optional.empty();
                }
                Violation badName = nameViolation(entry, seen);
                if (badName != null) {
                    violations.add(badName);
                    return Optional.empty();
                }
                String name = entry.getName();
                long entryRead = discard(zis, MAX_READ_BYTES - totalRead);
                totalRead += entryRead;
                if (totalRead > MAX_READ_BYTES) {
                    violations.add(new Violation(
                            "jar-too-much-content",
                            "The jar decompresses to more than %d bytes.".formatted(MAX_READ_BYTES),
                            "Shrink the jar's contents."));
                    return Optional.empty();
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && entryRead / compressed > MAX_RATIO) {
                    violations.add(new Violation(
                            "jar-compression-ratio",
                            "\"%s\" compresses at more than %d:1.".formatted(name, MAX_RATIO),
                            "Remove the entry; a legitimate class or resource does not compress this far."));
                    return Optional.empty();
                }
                names.add(name);
            }
        } catch (IOException e) {
            violations.add(new Violation(JAR_UNREADABLE, "The jar could not be read: " + e.getMessage(), REUPLOAD));
            return Optional.empty();
        }
        return Optional.of(names);
    }

    private static long discard(InputStream in, long limit) throws IOException {
        OutputStream sink = OutputStream.nullOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            sink.write(buf, 0, n);
            total += n;
            if (total > limit) {
                return total;
            }
        }
        return total;
    }

    private static boolean isSafeName(String name) {
        return !name.isEmpty()
                && !name.startsWith("/")
                && !name.contains("\\")
                && !name.equals("..")
                && !name.startsWith("../")
                && !name.contains("/../")
                && !name.endsWith("/..");
    }

    private void checkManifest(JarFile jar, List<Violation> violations) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            return;
        }
        var attrs = manifest.getMainAttributes();
        for (String denied : DENIED_MANIFEST_ATTRIBUTES) {
            if (attrs.getValue(denied) != null) {
                violations.add(new Violation(
                        "manifest-attribute-denied",
                        "The manifest sets %s, which a plugin may not use.".formatted(denied),
                        "Remove the %s entry from the manifest.".formatted(denied)));
            }
        }
    }

    private PluginDescriptor readDescriptor(JarFile jar, List<Violation> violations) throws IOException {
        JarEntry entry = jar.getJarEntry("META-INF/rahdar/plugin.json");
        if (entry == null) {
            violations.add(new Violation(
                    "descriptor-missing",
                    "The jar has no META-INF/rahdar/plugin.json.",
                    "Add the descriptor the build normally generates."));
            return null;
        }
        if (entry.getSize() > PluginDescriptorParser.MAX_BYTES) {
            violations.add(new Violation(
                    "descriptor-too-large",
                    "plugin.json is over the %d byte limit.".formatted(PluginDescriptorParser.MAX_BYTES),
                    "Trim plugin.json; it should be a few hundred bytes of metadata."));
            return null;
        }
        byte[] bytes;
        try (InputStream in = jar.getInputStream(entry)) {
            bytes = in.readNBytes(PluginDescriptorParser.MAX_BYTES + 1);
        }
        try {
            return descriptorParser.parse(bytes);
        } catch (PluginDescriptorException e) {
            violations.add(new Violation(
                    "descriptor-invalid", e.getMessage(), "Fix plugin.json against the published JSON Schema."));
            return null;
        }
    }

    private void checkAllowlist(List<String> names, PluginDescriptor descriptor, List<Violation> violations) {
        String base = descriptor.basePackage().replace('.', '/') + "/";
        String changelogPrefix = "db/changelog/plugin/" + descriptor.id() + "/";
        for (String name : names) {
            if (name.endsWith("/")) {
                continue;
            }
            boolean allowed = name.equals("META-INF/MANIFEST.MF")
                    || JarSignature.METADATA.matcher(name).matches()
                    || name.startsWith("META-INF/maven/")
                    || name.startsWith("META-INF/LICENSE")
                    || name.startsWith("META-INF/NOTICE")
                    || name.startsWith("META-INF/rahdar/")
                    || name.startsWith(changelogPrefix)
                    || name.startsWith(base)
                    || name.equals("module-info.class")
                    || name.matches("META-INF/versions/\\d+/" + Pattern.quote(base) + ".*");
            if (!allowed) {
                violations.add(new Violation(
                        "entry-not-allowlisted",
                        "\"%s\" is outside the plugin allowlist.".formatted(name),
                        "Remove it, or move it under %s, the plugin's changelog directory, or META-INF/rahdar/."
                                .formatted(descriptor.basePackage())));
            }
        }
    }

    private void checkDescriptorFields(
            PluginDescriptor descriptor, Set<String> otherBasePackages, List<Violation> violations) {
        checkId(descriptor.id(), violations);
        checkBasePackage(descriptor.basePackage(), otherBasePackages, violations);

        if (descriptor.contract() != Contract.VERSION) {
            violations.add(new Violation(
                    "contract-mismatch",
                    "This plugin was built against contract %d; Rahdar is at contract %d."
                            .formatted(descriptor.contract(), Contract.VERSION),
                    "Rebuild the plugin against the current rahdar API."));
        }

        checkRahdarRange(descriptor, violations);
        checkNamespaces(descriptor, violations);
        checkPermissionScopes(descriptor, violations);
        checkMetrics(descriptor, violations);
        checkIdentityProviders(descriptor, violations);
        checkApprovalProvider(descriptor, violations);
    }

    private void checkId(String id, List<Violation> violations) {
        if (id == null || !ID_PATTERN.matcher(id).matches() || id.length() > 50) {
            violations.add(
                    new Violation(
                            "id-invalid",
                            "\"%s\" is not a valid plugin id.".formatted(id),
                            "Use at least two kebab-case segments (vendor-name), lowercase letters and digits only, at most 50 characters."));
        } else if (id.startsWith("identity-")) {
            violations.add(new Violation(
                    "id-reserved", "Plugin ids starting with \"identity-\" are reserved.", "Choose a different id."));
        }
    }

    private void checkBasePackage(String basePackage, Set<String> otherBasePackages, List<Violation> violations) {
        if (basePackage == null || !PACKAGE_PATTERN.matcher(basePackage).matches()) {
            violations.add(new Violation(
                    "base-package-invalid",
                    "\"%s\" is not a valid Java package name.".formatted(basePackage),
                    "Use a lowercase dotted package name for basePackage."));
            return;
        }
        for (String reserved : RESERVED_PREFIXES) {
            if ((basePackage + ".").startsWith(reserved) || reserved.startsWith(basePackage + ".")) {
                violations.add(
                        new Violation(
                                "base-package-overlap",
                                "basePackage \"%s\" overlaps the reserved prefix \"%s\"."
                                        .formatted(basePackage, reserved),
                                "Choose a basePackage that is not inside, and does not contain, a Rahdar, JDK or framework package."));
            }
        }
        for (String other : otherBasePackages) {
            if ((basePackage + ".").startsWith(other + ".")
                    || (other + ".").startsWith(basePackage + ".")
                    || basePackage.equals(other)) {
                violations.add(new Violation(
                        "base-package-overlap",
                        "basePackage \"%s\" overlaps another installed plugin's basePackage \"%s\"."
                                .formatted(basePackage, other),
                        "Choose a basePackage no other installed plugin uses."));
            }
        }
    }

    private void checkRahdarRange(PluginDescriptor descriptor, List<Violation> violations) {
        PluginDescriptor.Rahdar rahdar = descriptor.rahdar();
        if (rahdar == null || CalVer.parse(rahdar.since()).isEmpty()) {
            violations.add(new Violation(
                    "rahdar-since-invalid",
                    "rahdar.since is missing or not a YYYY.MM.PATCH version.",
                    "Set rahdar.since to the Rahdar version this plugin was built against."));
            return;
        }
        if (!rahdarVersion.isKnown()) {
            violations.add(new Violation(
                    "rahdar-version-unknown",
                    "The running Rahdar version could not be determined, so the compatibility range was not checked.",
                    "Nothing to fix; this is expected on a SNAPSHOT or IDE build of Rahdar.",
                    Violation.Severity.WARNING));
            return;
        }
        RahdarVersion.Compatibility compat = rahdarVersion.check(rahdar.since(), rahdar.until());
        switch (compat) {
            case TOO_OLD ->
                violations.add(new Violation(
                        "rahdar-too-old",
                        "This plugin requires Rahdar %s or later.".formatted(rahdar.since()),
                        "Upgrade Rahdar, or lower rahdar.since if the plugin does not really need it."));
            case TOO_NEW ->
                violations.add(new Violation(
                        "rahdar-too-new",
                        "This plugin declares rahdar.until=%s, older than the running Rahdar."
                                .formatted(rahdar.until()),
                        "Rebuild the plugin against the current Rahdar and raise rahdar.until."));
            case UNKNOWN ->
                violations.add(new Violation(
                        "rahdar-until-invalid",
                        "rahdar.until \"%s\" is not a YYYY.MM.PATCH version or a YYYY.MM.* wildcard."
                                .formatted(rahdar.until()),
                        "Fix rahdar.until."));
            case COMPATIBLE -> {
                // in range: nothing to report
            }
        }
    }

    private void checkNamespaces(PluginDescriptor descriptor, List<Violation> violations) {
        String id = descriptor.id();
        if (id == null) {
            return;
        }
        String permPrefix = id + ":";
        for (var permission : descriptor.permissions()) {
            if (permission.action() == null || !permission.action().startsWith(permPrefix)) {
                violations.add(new Violation(
                        "permission-namespace",
                        "Permission \"%s\" is not namespaced under \"%s\".".formatted(permission.action(), permPrefix),
                        "Prefix every permission action with \"%s\".".formatted(permPrefix)));
            }
        }
        String settingPrefix = id + ".";
        for (String key : descriptor.settingKeys()) {
            if (key == null || !key.startsWith(settingPrefix)) {
                violations.add(new Violation(
                        "setting-namespace",
                        "Setting key \"%s\" is not namespaced under \"%s\".".formatted(key, settingPrefix),
                        "Prefix every setting key with \"%s\".".formatted(settingPrefix)));
            }
        }
        for (String topic : descriptor.streamTopics()) {
            if (topic == null || !(topic.equals(id) || topic.startsWith(id + "."))) {
                violations.add(new Violation(
                        "topic-namespace",
                        "Stream topic \"%s\" is not namespaced under \"%s\".".formatted(topic, id),
                        "Name the topic \"%s\" or \"%s.<name>\".".formatted(id, id)));
            }
        }
        checkMcpTools(descriptor, id, violations);
        // The @ConfigurationProperties prefix itself (rahdar.plugins.<id>) is checked
        // against bytecode in BytecodeChecks, which needs the class file, not just the descriptor.
    }

    /** Every permission says where it takes effect: a scope, and for a resource the kinds it acts on. */
    private void checkPermissionScopes(PluginDescriptor descriptor, List<Violation> violations) {
        for (var permission : descriptor.permissions()) {
            String action = permission.action();
            if (permission.globalOnly() != null) {
                violations.add(new Violation(
                        "permission-global-only",
                        "Permission \"%s\" declares globalOnly, which scope replaced.".formatted(action),
                        "Remove globalOnly and declare scope: \"global\" for a permission checked without a cluster,"
                                + " \"cluster\" for one checked against a cluster, or \"resource\" for one checked"
                                + " against a queue or address."));
            }
            boolean resource = SCOPE_RESOURCE.equals(permission.scope());
            if (permission.scope() == null || !SCOPES.contains(permission.scope())) {
                violations.add(new Violation(
                        "permission-scope",
                        "Permission \"%s\" declares scope \"%s\", which is not one of global, cluster or resource."
                                .formatted(action, permission.scope()),
                        "Declare scope as \"global\", \"cluster\" or \"resource\"."));
            } else if (resource && permission.resourceTypes().isEmpty()) {
                violations.add(new Violation(
                        RESOURCE_TYPES_CODE,
                        "Permission \"%s\" has scope \"resource\" but names no resourceTypes.".formatted(action),
                        "Declare resourceTypes as [\"subscription\"], [\"destination\"] or both."));
            } else if (!resource && !permission.resourceTypes().isEmpty()) {
                violations.add(new Violation(
                        RESOURCE_TYPES_CODE,
                        "Permission \"%s\" names resourceTypes but its scope is \"%s\"."
                                .formatted(action, permission.scope()),
                        "Remove resourceTypes, or change the scope to \"resource\"."));
            }
            permission.resourceTypes().stream()
                    .filter(kind -> !RESOURCE_TYPES.contains(kind))
                    .forEach(kind -> violations.add(new Violation(
                            RESOURCE_TYPES_CODE,
                            "Permission \"%s\" names resource type \"%s\", which is not subscription or destination."
                                    .formatted(action, kind),
                            "Name \"subscription\", \"destination\" or both.")));
            permission.requires().stream()
                    .filter(String::isBlank)
                    .forEach(required -> violations.add(new Violation(
                            "permission-requires",
                            "Permission \"%s\" requires a blank permission name.".formatted(action),
                            "Name the permissions a role must hold with it, or remove requires.")));
        }
    }

    private void checkMcpTools(PluginDescriptor descriptor, String id, List<Violation> violations) {
        String mcpPrefix = id.replace('-', '_') + "_";
        Map<String, PluginDescriptor.Permission> declared = new HashMap<>();
        descriptor.permissions().forEach(p -> declared.put(p.action(), p));
        for (var tool : descriptor.mcpTools()) {
            if (tool.name() == null || !tool.name().startsWith(mcpPrefix)) {
                violations.add(new Violation(
                        "mcp-tool-namespace",
                        "Assistant tool \"%s\" is not namespaced under \"%s\".".formatted(tool.name(), mcpPrefix),
                        "Prefix every tool name with \"%s\".".formatted(mcpPrefix)));
            }
            // Rahdar checks this permission before the tool runs (ADR-0114), so it must be one the
            // plugin declares: an undeclared one could never be granted, and the tool would be dead.
            PluginDescriptor.Permission permission = declared.get(tool.permission());
            if (permission == null) {
                violations.add(new Violation(
                        "mcp-tool-permission",
                        "Assistant tool \"%s\" is guarded by \"%s\", which the plugin does not declare."
                                .formatted(tool.name(), tool.permission()),
                        DECLARE_PERMISSION));
            } else if (tool.scope() != null && !tool.scope().equals(permission.scope())) {
                violations.add(new Violation(
                        "mcp-tool-scope",
                        "Assistant tool \"%s\" has scope \"%s\" but its permission \"%s\" has scope \"%s\"."
                                .formatted(tool.name(), tool.scope(), tool.permission(), permission.scope()),
                        "Give the tool the scope of its permission, or guard it with a permission of scope \"%s\"."
                                .formatted(tool.scope())));
            }
            checkResourceTool(tool, permission, violations);
        }
    }

    /** A resource tool names the argument that holds the queue or address, and its kind; no other tool does. */
    private void checkResourceTool(
            PluginDescriptor.McpTool tool, PluginDescriptor.Permission permission, List<Violation> violations) {
        boolean resource = SCOPE_RESOURCE.equals(tool.scope());
        boolean named = tool.resourceArg() != null && !tool.resourceArg().isBlank();
        if (resource && (!named || tool.resourceType() == null)) {
            violations.add(new Violation(
                    MCP_TOOL_RESOURCE,
                    "Assistant tool \"%s\" has scope \"resource\" but does not name resourceArg and resourceType."
                            .formatted(tool.name()),
                    "Name the tool's string argument that holds the subscription or destination, as resourceArg, and its"
                            + " resourceType, \"subscription\" or \"destination\"."));
        } else if (!resource && (named || tool.resourceType() != null)) {
            violations.add(new Violation(
                    MCP_TOOL_RESOURCE,
                    "Assistant tool \"%s\" names a resourceArg or resourceType but its scope is \"%s\"."
                            .formatted(tool.name(), tool.scope()),
                    "Remove them, or change the scope to \"resource\"."));
        } else if (resource && permission != null && !permission.resourceTypes().contains(tool.resourceType())) {
            violations.add(new Violation(
                    MCP_TOOL_RESOURCE,
                    "Assistant tool \"%s\" acts on a %s but its permission \"%s\" acts on %s."
                            .formatted(tool.name(), tool.resourceType(), tool.permission(), permission.resourceTypes()),
                    "Name a resourceType the permission's resourceTypes include."));
        }
    }

    /** ADR-0113: metrics are namespaced, readable with a declared permission, and rules watch declared ones. */
    private void checkMetrics(PluginDescriptor descriptor, List<Violation> violations) {
        String id = descriptor.id();
        if (id == null) {
            return;
        }
        Set<String> permissions = new HashSet<>();
        descriptor.permissions().forEach(p -> permissions.add(p.action()));
        Set<String> metrics = new HashSet<>();
        for (var metric : descriptor.metrics()) {
            String name = metric.name();
            if (name == null || !METRIC_NAME.matcher(name).matches() || !name.startsWith(id + ":")) {
                violations.add(new Violation(
                        "metric-name",
                        "Metric \"%s\" is not a valid name under \"%s:\".".formatted(name, id),
                        "Name every metric \"%s:<name>\", with lowercase letters, digits, '.' and '_' after the colon."
                                .formatted(id)));
            } else if (!metrics.add(name)) {
                violations.add(new Violation(
                        "metric-duplicate",
                        "Metric \"%s\" is declared twice.".formatted(name),
                        "Declare each metric once."));
            }
            if (!METRIC_UNITS.contains(metric.unit())) {
                violations.add(new Violation(
                        "metric-unit",
                        "Metric \"%s\" has the unit \"%s\".".formatted(name, metric.unit()),
                        "Use one of " + METRIC_UNITS + "."));
            }
            if (!permissions.contains(metric.permission())) {
                violations.add(new Violation(
                        "metric-permission",
                        "Metric \"%s\" is read with \"%s\", which the plugin does not declare."
                                .formatted(name, metric.permission()),
                        DECLARE_PERMISSION));
            }
        }
        checkAlertRules(descriptor, metrics, violations);
    }

    private void checkAlertRules(PluginDescriptor descriptor, Set<String> metrics, List<Violation> violations) {
        Set<String> keys = new HashSet<>();
        for (var rule : descriptor.alertRules()) {
            if (rule.key() == null || rule.key().isBlank() || !keys.add(rule.key())) {
                violations.add(new Violation(
                        "alert-rule-key",
                        "Alert rule key \"%s\" is missing or used twice.".formatted(rule.key()),
                        "Give every alert rule its own stable key."));
            }
            if (!metrics.contains(rule.metric())) {
                violations.add(new Violation(
                        "alert-rule-metric",
                        "Alert rule \"%s\" watches \"%s\", which the plugin does not declare under metrics."
                                .formatted(rule.key(), rule.metric()),
                        "Point the rule at one of the plugin's declared metrics."));
            }
        }
    }

    /** ADR-0156: sign-in providers are namespaced like metrics, unique, and have a label the login screen can show. */
    private void checkIdentityProviders(PluginDescriptor descriptor, List<Violation> violations) {
        String id = descriptor.id();
        if (id == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (var provider : descriptor.identityProviders()) {
            String name = provider.id();
            if (name == null || !METRIC_NAME.matcher(name).matches() || !name.startsWith(id + ":")) {
                violations.add(new Violation(
                        "identity-provider-id",
                        "Identity provider \"%s\" is not a valid id under \"%s:\".".formatted(name, id),
                        "Name every provider \"%s:<name>\", with lowercase letters, digits, '.' and '_' after the colon."
                                .formatted(id)));
            } else if (!seen.add(name)) {
                violations.add(new Violation(
                        "identity-provider-duplicate",
                        "Identity provider \"%s\" is declared twice.".formatted(name),
                        "Declare each provider once."));
            }
            String label = provider.label();
            if (label == null || label.isBlank() || label.length() > 64) {
                violations.add(new Violation(
                        "identity-provider-label",
                        "Identity provider \"%s\" needs a label of 1 to 64 characters for the login screen."
                                .formatted(name),
                        "Give the provider a short label such as \"Corporate directory\"."));
            }
        }
    }

    /** ADR-0179: the approver permission is one the plugin itself declares, so Rahdar can offer it in the role editor. */
    private void checkApprovalProvider(PluginDescriptor descriptor, List<Violation> violations) {
        var provider = descriptor.approvalProvider();
        if (provider == null) {
            return;
        }
        String permission = provider.approverPermission();
        if (permission == null
                || descriptor.permissions().stream().noneMatch(p -> p.action().equals(permission))) {
            violations.add(new Violation(
                    "approval-provider-permission",
                    "The approval provider names the approver permission \"%s\", which the plugin does not declare under permissions."
                            .formatted(permission),
                    DECLARE_PERMISSION));
        }
    }
}
