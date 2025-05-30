/*
 * Copyright IBM Corp. and others 1991
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] https://openjdk.org/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR GPL-2.0-only WITH OpenJDK-assembly-exception-1.0
 */
package com.ibm.j9ddr;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINEST;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.stream.ImageInputStream;

import com.ibm.j9ddr.logging.LoggerNames;

// TODO: Lazy initializing has been removed.  Need to decide if it stays out.
public final class StructureReader {
	public static final int VERSION = 1;
	public static final int J9_STRUCTURES_EYECATCHER = 0xFACEDEB8; // eyecatcher / magic identifier for a J9 structure file
	private Map<String, StructureDescriptor> structures = null;

	private String packageDotBaseName;
	private String pointerDotName;
	private String pointerSlashName;
	private String structureDotName;
	private String structureSlashName;

	private static final Logger logger = Logger.getLogger(LoggerNames.LOGGER_STRUCTURE_READER);
	private StructureHeader header;

	public static final Class<?>[] STRUCTURE_CONSTRUCTOR_SIGNATURE = new Class<?>[] { Long.TYPE };
	public static final byte BIT_FIELD_FORMAT_LITTLE_ENDIAN = 1;
	public static final byte BIT_FIELD_FORMAT_BIG_ENDIAN = 2;
	public static final int BIT_FIELD_CELL_SIZE = 32;

	private static final Pattern MULTI_LINE_COMMENT_PATTERN = Pattern.compile(Pattern.quote("/*") + ".*?" + Pattern.quote("*/") , Pattern.DOTALL);
	private static final Pattern SINGLE_LINE_COMMENT_PATTERN = Pattern.compile(Pattern.quote("//") + ".*$", Pattern.MULTILINE);
	private static final Pattern MAP_PATTERN = Pattern.compile("(.*?)=(.*?)$", Pattern.MULTILINE);
	private static final Pattern CONSTANT_PATTERN = Pattern.compile("(.+?)\\.(.+?)=(.+)$", Pattern.MULTILINE);

	private Long packageVersion;
	private String basePackage = DDR_VERSIONED_PACKAGE_PREFIX;

	private final StructureTypeManager typeManager;

	/* Patterns for cleaning types */
	/* Pattern that matches a 1 or more characters not including ']' that occur after [ */
	private static final Pattern CONTENTS_OF_ARRAY_PATTERN = Pattern.compile("(?<=\\[).*?(?=\\])");

	private static final Pattern SPACES_AFTER_SQUARE_BRACKETS_PATTERN = Pattern.compile("(?<=\\])\\s+");

	private static final Pattern SPACES_BEFORE_SQUARE_BRACKETS_PATTERN = Pattern.compile("\\s+(?=\\[)");

	private static final Pattern SPACES_AFTER_ASTERISKS_PATTERN = Pattern.compile("(?<=\\*)\\s+");

	private static final Pattern SPACES_BEFORE_ASTERISKS_PATTERN = Pattern.compile("\\s+(?=\\*)");

	// VM Version constants
	private static final String VM_MAJOR_VERSION = "VM_MAJOR_VERSION";
	private static final String VM_MINOR_VERSION = "VM_MINOR_VERSION";
	private static final String ARM_SPLIT_DDR_HACK = "ARM_SPLIT_DDR_HACK";
	private static final String DDRALGORITHM_STRUCTURE_NAME = "DDRAlgorithmVersions";

	public static final String DDR_VERSIONED_PACKAGE_PREFIX = "com.ibm.j9ddr.vm";

	public static enum PackageNameType {
		PACKAGE_DOT_BASE_NAME,
		POINTER_PACKAGE_DOT_NAME,
		POINTER_PACKAGE_SLASH_NAME,
		STRUCTURE_PACKAGE_DOT_NAME,
		STRUCTURE_PACKAGE_SLASH_NAME;
	}

	/**
	 * Initialize this reader from the supplied data.
	 * The ImageInputStream must be validated and already be positioned to point to
	 * the first byte of the DDR Structure Data. The ByteOrder of the ImageInputStream
	 * will be adjusted to match the file being read. (The DDR Structure data is written
	 * in the ByteOrder of the platform that created the core file.)
	 */
	public StructureReader(ImageInputStream in) throws IOException {
		parse(in);
		setStream();
		applyAliases();
		addCompatibilityConstants();
		loadAuxFieldInfo();
		typeManager = new StructureTypeManager(getStructures());
	}

	/**
	 * Read a stream in the J9DDRStructStore superset format.
	 */
	public StructureReader(InputStream in) throws IOException {
		parse(in);
		setStream();
		typeManager = new StructureTypeManager(getStructures());
	}

	public StructureHeader getHeader() {
		return header;
	}

	/**
	 * Sets the based on the DDRAlgorithmVersions field in the blob.
	 */
	private void setStream() {
		StructureDescriptor version = structures.get(DDRALGORITHM_STRUCTURE_NAME);
		long vmMajorVersion = 2; // default stream: 29
		long vmMinorVersion = 9;

		/* JAZZ 103906 : A hack was introduced when we split jvm.29 for ARM development.
		 * The goal was to keep using the same values of VM_MAJOR_VERSION and
		 * VM_MINOR_VERSION as in jvm.29 but now support ARM in this separate stream.
		 * Therefore, we needed a new way to differentiate the special ARM jvm.29 stream
		 * and this is the reason why the ARM_SPLIT_DDR_HACK field was added.
		 */
		String versionFormat = "%02d";
		if (version != null) {
			for (ConstantDescriptor constant : version.getConstants()) {
				String constantName = constant.getName();
				if (constantName.equals(VM_MAJOR_VERSION)) {
					vmMajorVersion = constant.getValue();
				} else if (constantName.equals(VM_MINOR_VERSION)) {
					vmMinorVersion = constant.getValue() / 10;
				} else if (constantName.equals(ARM_SPLIT_DDR_HACK) && constant.getValue() == 1) {
					versionFormat = "%02d_00";
				}
			}
		}

		packageVersion = Long.valueOf((vmMajorVersion * 10) + vmMinorVersion); // stream is a 2 digit value

		String versionSuffix = String.format(versionFormat, packageVersion);

		packageDotBaseName = DDR_VERSIONED_PACKAGE_PREFIX + versionSuffix;
		pointerDotName = packageDotBaseName + ".pointer.generated";
		pointerSlashName = pointerDotName.replace('.', '/') + '/';
		structureDotName = packageDotBaseName + ".structure";
		structureSlashName = structureDotName.replace('.', '/') + '/';
	}

	public String getBasePackage() {
		return basePackage;
	}

	/**
	 * Get the package version number, derived from fields of DDRAlgorithmVersions in the blob.
	 *
	 * @return the package version number
	 */
	public long getPackageVersion() {
		if (packageVersion == null) {
			throw new IllegalStateException("The DDR version information is not yet available");
		}

		return packageVersion.longValue();
	}

	/**
	 * Get the package name that should be used including the version information
	 * @param type
	 * @return
	 */
	public String getPackageName(PackageNameType type) {
		if (packageVersion == null) {
			throw new IllegalStateException("The DDR version information is not yet available");
		}

		switch (type) {
		case PACKAGE_DOT_BASE_NAME:
			return packageDotBaseName;
		case POINTER_PACKAGE_DOT_NAME:
			return pointerDotName;
		case POINTER_PACKAGE_SLASH_NAME:
			return pointerSlashName;
		case STRUCTURE_PACKAGE_DOT_NAME:
			return structureDotName;
		case STRUCTURE_PACKAGE_SLASH_NAME:
			return structureSlashName;
		default:
			throw new IllegalStateException("Unexpected PackageNameType");
		}
	}

	private void applyAliases() throws IOException {
		Map<String, String> aliasMap = loadAliasMap(getAliasVersion());

		for (StructureDescriptor thisStruct : structures.values()) {
			for (FieldDescriptor thisField : thisStruct.fields) {
				thisField.applyAliases(aliasMap);
			}
		}
	}

	private void addCompatibilityConstants() throws IOException {
		String resource = "/com/ibm/j9ddr/CompatibilityConstants" + getPackageVersion() + ".dat";

		try (InputStream inputStream = StructureReader.class.getResourceAsStream(resource)) {
			if (inputStream != null) {
				addCompatibilityConstants(inputStream);
			}
		}
	}

	public void addCompatibilityConstants(InputStream inputStream) throws IOException {
		Map<String, Map<String, Long>> map = new HashMap<>();
		String text = stripComments(loadUTF8(inputStream));

		for (Matcher matcher = CONSTANT_PATTERN.matcher(text); matcher.find();) {
			String type = matcher.group(1).trim();
			String name = matcher.group(2).trim();
			String value = matcher.group(3).trim();

			if (type.isEmpty() || name.isEmpty() || value.isEmpty()) {
				throw new IOException("Malformed constant: " + matcher.group());
			}

			Map<String, Long> constants = map.get(type);

			if (constants == null) {
				constants = new HashMap<>();
				map.put(type, constants);
			}

			try {
				constants.put(name, Long.decode(value));
			} catch (NumberFormatException e) {
				throw new IOException("Malformed constant: " + matcher.group(), e);
			}
		}

		for (StructureDescriptor structure : structures.values()) {
			Map<String, Long> constants = map.remove(structure.getName());

			if (constants == null) {
				/* nothing applies to this structure */
				continue;
			}

			/* remove entries that are already present */
			for (ConstantDescriptor constant : structure.constants) {
				constants.remove(constant.name);
			}

			/* add missing constants */
			for (Map.Entry<String, Long> entry : constants.entrySet()) {
				structure.constants.add(new ConstantDescriptor(entry.getKey(), entry.getValue()));
			}
		}

		/* add default constants for new types */
		for (Map.Entry<String, Map<String, Long>> entry : map.entrySet()) {
			String name = entry.getKey();
			String line = "S|" + name + "|" + name + "Pointer|";
			StructureDescriptor structure = new StructureDescriptor(line);

			for (Map.Entry<String, Long> constant : entry.getValue().entrySet()) {
				structure.constants.add(new ConstantDescriptor(constant.getKey(), constant.getValue()));
			}

			structures.put(structure.getName(), structure);
		}
	}

	public static Map<String, String> loadAliasMap(long version) throws IOException {
		return loadAliasMap(Long.toString(version));
	}

	private static Map<String, String> loadAliasMap(String version) throws IOException {
		String mapData = loadAliasMapData(version);

		mapData = stripComments(mapData);

		Map<String, String> aliasMap = new HashMap<>();
		Matcher mapMatcher = MAP_PATTERN.matcher(mapData);

		while (mapMatcher.find()) {
			String from = mapMatcher.group(1);
			String to = mapMatcher.group(2);

			aliasMap.put(from.trim(), to.trim());
		}

		return Collections.unmodifiableMap(aliasMap);
	}

	private void loadAuxFieldInfo() throws IOException {
		String resource = "/com/ibm/j9ddr/AuxFieldInfo" + getPackageVersion() + ".dat";

		try (InputStream stream = StructureReader.class.getResourceAsStream(resource)) {
			if (stream != null) {
				loadAuxFieldInfo(stream);
			}
		}
	}

	public void loadAuxFieldInfo(InputStream stream) throws IOException {
		Map<String, Map<String, String>> fieldMap = new HashMap<>();
		Pattern fieldPattern = Pattern.compile("(.+?)\\.(.+?)=(.+)$", Pattern.MULTILINE);
		String text = stripComments(loadUTF8(stream));

		for (Matcher matcher = fieldPattern.matcher(text); matcher.find();) {
			String typeName = matcher.group(1).trim();
			String fieldName = matcher.group(2).trim();
			String fieldType = matcher.group(3).trim();

			if (typeName.isEmpty() || fieldName.isEmpty() || fieldType.isEmpty()) {
				throw new IOException("Malformed field: " + matcher.group());
			}

			Map<String, String> infoMap = fieldMap.get(typeName);

			if (infoMap == null) {
				infoMap = new HashMap<>();
				fieldMap.put(typeName, infoMap);
			}

			String previous = infoMap.put(fieldName, fieldType);

			if (previous != null) {
				throw new IOException("Duplicate field: " + matcher.group());
			}
		}

		boolean debug = Boolean.getBoolean("aux.field.info.debug");

		for (StructureDescriptor structure : structures.values()) {
			String typeName = structure.getName();
			Map<String, String> infoMap = fieldMap.remove(typeName);

			if (infoMap == null) {
				// No required or optional fields for this structure.
				continue;
			}

			List<FieldDescriptor> fields = structure.getFields();

			// Mark required fields and remove corresponding entries from infoMap.
			for (FieldDescriptor field : fields) {
				String fieldName = field.getName();
				String fieldType = infoMap.remove(fieldName);

				if (fieldType == null) {
					// No auxilliary information for this field.
				} else if (fieldType.equals("required")) {
					field.required = true;
				} else {
					field.optional = true;
					if (debug) {
						field.declaredType = fieldType;
						field.type = fieldType;
					}
				}
			}

			// Remaining entries in infoMap are for missing fields.
			for (Map.Entry<String, String> entry : infoMap.entrySet()) {
				String fieldName = entry.getKey();
				String fieldType = entry.getValue();
				FieldDescriptor field;

				if (fieldType.equals("required")) {
					field = new FieldDescriptor(0, "?", "?", fieldName, fieldName);
					field.required = true;
				} else {
					field = new FieldDescriptor(0, fieldType, fieldType, fieldName, fieldName);
					field.optional = true;
				}

				field.present = false;
				fields.add(field);
			}
		}

		// Remaining entries in fieldMap are for missing types.
		for (Map.Entry<String, Map<String, String>> typeEntry : fieldMap.entrySet()) {
			StructureDescriptor type = new StructureDescriptor();
			String typeName = typeEntry.getKey();
			List<FieldDescriptor> fields = new ArrayList<>();

			type.name = typeName;
			type.constants = new ArrayList<>();
			type.fields = fields;

			structures.put(typeName, type);

			for (Map.Entry<String, String> fieldEntry : typeEntry.getValue().entrySet()) {
				String fieldName = fieldEntry.getKey();
				String fieldType = fieldEntry.getValue();
				FieldDescriptor field;

				if (fieldType.equals("required")) {
					field = new FieldDescriptor(0, "?", "?", fieldName, fieldName);
					field.required = true;
				} else {
					field = new FieldDescriptor(0, fieldType, fieldType, fieldName, fieldName);
					field.optional = true;
				}

				field.present = false;
				fields.add(field);
			}
		}
	}

	private static String stripComments(String mapData) {
		mapData = filterOutPattern(mapData, MULTI_LINE_COMMENT_PATTERN);
		mapData = filterOutPattern(mapData, SINGLE_LINE_COMMENT_PATTERN);
		return mapData;
	}

	private String getAliasVersion() {
		long version = getPackageVersion();
		String variant = "";

		if ((version == 29) && !getBuildFlagValue("J9BuildFlags", "J9VM_OPT_USE_OMR_DDR", false)) {
			/*
			 * For blobs generated from the current stream but with legacy tools,
			 * load a variant of the alias map.
			 */
			variant = "-edg";
		}

		return version + variant;
	}

	private static String loadAliasMapData(String version) throws IOException {
		String streamAliasMapResource = "/com/ibm/j9ddr/StructureAliases" + version + ".dat";

		try (InputStream is = StructureReader.class.getResourceAsStream(streamAliasMapResource)) {
			if (null == is) {
				throw new RuntimeException("Failed to load alias map from resource: " + streamAliasMapResource + " - cannot continue");
			}

			return loadUTF8(is);
		}
	}

	private static String loadUTF8(InputStream is) throws IOException {
		try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
			StringBuilder builder = new StringBuilder();
			char[] buffer = new char[4096];
			int read;

			while ((read = reader.read(buffer)) != -1) {
				builder.append(buffer, 0, read);
			}

			return builder.toString();
		}
	}

	/**
	 * Get a list of the structures defined by this reader
	 * @return Set interface for the structure names
	 */
	public Set<String> getStructureNames() {
		return structures.keySet();
	}

	/**
	 * Test to see if a structure has been read in and hence available
	 * @param name name of the structure to look for
	 * @return true if the structure exists, false if not
	 */
	public boolean hasStructure(String name) {
		return structures.containsKey(name);
	}

	public int getStructureSizeOf(String structureName) {
		if (!hasStructure(structureName)) {
			return 0;
		}
		return structures.get(structureName).getSizeOf();
	}

	public Collection<StructureDescriptor> getStructures() {
		return structures.values();
	}

	/**
	 * Get a map of the fields for a particular structure and their offsets.
	 * @param structureName name of the structure
	 * @return map of the fields as FieldName, Offset pair
	 */
	public List<FieldDescriptor> getFields(String structureName) {
		StructureDescriptor structure = structures.get(structureName);

		if (structure == null) {
			throw new IllegalArgumentException("The structure [" + structureName + "] was not found");
		}

		return structure.fields;
	}

	public List<ConstantDescriptor> getConstants(String structureName) {
		StructureDescriptor structure = structures.get(structureName);

		if (structure == null) {
			throw new IllegalArgumentException("The structure [" + structureName + "] was not found");
		}

		return structure.constants;
	}

	private void parse(InputStream inputStream) throws IOException {
		header = new StructureHeader(inputStream);
		structures = new HashMap<>();
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		StructureDescriptor structure = null;

		for (;;) {
			String line = reader.readLine();
			if (line == null) {
				break;
			}
			int hash = line.indexOf('#');
			if (hash >= 0) {
				// remove comments
				line = line.substring(0, hash);
			}
			if (line.isEmpty()) {
				// ignore blank lines
				continue;
			}
			char type = line.charAt(0);
			switch (type) {
			case 'S':
				structure = new StructureDescriptor(line);
				structures.put(structure.getName(), structure);
				break;
			case 'F':
				if (structure == null) {
					throw new IllegalArgumentException("Superset stream is missing structure start line");
				}
				Collection<FieldDescriptor> fields = FieldDescriptor.inflate(line);
				structure.fields.addAll(fields);
				break;
			case 'C':
				if (structure == null) {
					throw new IllegalArgumentException("Superset stream is missing structure start line");
				}
				ConstantDescriptor constant = new ConstantDescriptor(line);
				structure.constants.add(constant);
				break;
			default:
				throw new IllegalArgumentException("Superset stream contains unknown line: " + line);
			}
		}
	}

	public void removeReservedTypeNames() {
		for (Iterator<String> names = structures.keySet().iterator(); names.hasNext();) {
			switch (names.next()) {
			case "module":
			case "record":
			case "var":
				names.remove();
				break;
			default:
				break;
			}
		}
	}

	/**
	 * Add to the existing set of structures already contained in this reader. Note that
	 * this will replace any definitions already contained within the reader.
	 *
	 * @param ddrStream stream to add the structures from
	 * @throws IOException
	 */
	public void addStructures(ImageInputStream ddrStream) throws IOException {
		StructureHeader fragmentHeader = new StructureHeader(ddrStream);
		checkBlobVersion();
		if (header.getSizeofBool() != fragmentHeader.getSizeofBool()) {
			throw new IOException("Invalid fragment definition : size of boolean is not the same");
		}
		if (header.getSizeofUDATA() != fragmentHeader.getSizeofUDATA()) {
			throw new IOException("Invalid fragment definition : size of UDATA is not the same");
		}
		parseStructures(ddrStream, fragmentHeader);
	}

	/**
	 * Parse the supplied data and extract the structure information.
	 * It is expected that the ImageInputStream already points to the start of the
	 * DDR structure data. The ByteOrder of the ImageInputStream will be adjusted
	 * to match the file being read. (The DDR Structure data is written in the
	 * ByteOrder of the platform that created the core file.)
	 *
	 * This parse is called when the blob is initially loaded.
	 *
	 * The structures are lazily loaded in that the name of the structure is
	 * identified but not processed until a subsequent call requests it.
	 * 
	 * @param ddrStream an open stream on the blob to be read
	 * @throws IOException re-throws any exceptions from the ImageInputStream
	 */
	private void parse(ImageInputStream ddrStream) throws IOException {
		logger.logp(FINE, null, null, "Parsing structures. Start address = {0}", Long.toHexString(ddrStream.getStreamPosition()));
		header = new StructureHeader(ddrStream);
		checkBlobVersion();
		parseStructures(ddrStream, header);
	}

	/**
	 * Checks that the blob version is supported and correctly sets the ByteOrder.
	 *
	 * @param ddrStream blob stream to check
	 * @throws IOException thrown if the version is not supported
	 */
	private void checkBlobVersion() throws IOException {
		logger.logp(FINE, null, null, "Stream core structure version = {0}", header.getCoreVersion());

		if (header.getCoreVersion() > VERSION) {
			throw new IOException("Core structure version " + header.getCoreVersion() + " > StructureReader version " + VERSION);
		}
	}

	/**
	 * Parse the structures from a supplied stream.
	 *
	 * @param ddrStream stream to read from, assumes that the stream is correctly positioned
	 * @param header blob header
	 * @throws IOException
	 */
	private void parseStructures(ImageInputStream ddrStream, StructureHeader header) throws IOException {
		logger.logp(FINER, null, null, "structDataSize={0}, stringTableDataSize={1}, structureCount={2}",
				new Object[] { header.getStructDataSize(), header.getStringTableDataSize(), header.getStructureCount() });

		// This line must come after the header reads above or the offsets will be wrong.
		long ddrStringTableStart = ddrStream.getStreamPosition() + header.getStructDataSize();

		logger.logp(FINER, null, null, "ddrStringTableStart=0x{0}", Long.toHexString(ddrStringTableStart));

		if (structures == null) {
			// initialize the structure map with a sensible initial capacity
			structures = new HashMap<>(header.getStructureCount());
		}

		for (int i = 0; i < header.getStructureCount(); i++) {
			logger.logp(FINER, null, null, "Reading structure on iteration {0}", i);
			StructureDescriptor structure = new StructureDescriptor();
			structure.name = decodeTypeName(readString(ddrStream, ddrStringTableStart));
			if (structure.name == null) {
				logger.logp(FINE, null, null, "Structure name was null for structure {0}", i);
				throw new IllegalArgumentException("Structure name was null for structure " + i);
			} else if (structure.name.isEmpty()) {
				logger.logp(FINE, null, null, "Structure name was blank for structure {0}", i);
				throw new IllegalArgumentException("No name found for structure " + i);
			}
			logger.logp(FINE, null, null, "Reading structure {0}", structure.name);

			structure.superName = decodeTypeName(readString(ddrStream, ddrStringTableStart));
			structure.sizeOf = ddrStream.readInt();
			int numberOfFields = ddrStream.readInt();
			structure.fields = new ArrayList<>(numberOfFields);
			int numberOfConstants = ddrStream.readInt();
			structure.constants = new ArrayList<>(numberOfConstants);

			logger.logp(FINER, null, null, "{0} super {1} sizeOf {2}",
					new Object[] { structure.name, structure.superName, structure.sizeOf });

			for (int j = 0; j < numberOfFields; j++) {
				String declaredName = readString(ddrStream, ddrStringTableStart);

				// Inline anonymous structures are handled by stacking the fields, separated by ".".
				String name = declaredName.replace(".", "$");

				String declaredType = readString(ddrStream, ddrStringTableStart);
				if (name.equals("hashCode")) {
					name = "_hashCode";
				}

				// Type is unaliased later
				int offset = ddrStream.readInt();
				FieldDescriptor field = new FieldDescriptor(offset, declaredType, declaredType, name, declaredName);
				structure.fields.add(field);
				logger.logp(FINEST, null, null, "Field: {0}.{1} offset {2}, declaredType {3}",
						new Object[] { structure.name, name, offset, declaredType, declaredType });
			}

			for (int j = 0; j < numberOfConstants; j++) {
				String name = readString(ddrStream, ddrStringTableStart);
				long value = ddrStream.readLong();
				ConstantDescriptor constant = new ConstantDescriptor(name, value);
				structure.constants.add(constant);
				logger.logp(FINEST, null, null, "Constant: {0}.{1}={2}",
						new Object[] { structure.name, name, value });
			}

			structures.put(structure.name, structure);
		}

		logger.logp(FINE, null, null, "Finished parsing structures");
	}

	private static String decodeTypeName(String typeName) {
		if (typeName != null) {
			int index = typeName.indexOf("__");

			if (index >= 0) {
				StringBuilder buffer = new StringBuilder();
				int start = 0;

				do {
					if (index == start) {
						buffer.append("__");
					} else {
						buffer.append(typeName, start, index).append("$");
					}

					start = index + 2;
					index = typeName.indexOf("__", start);
				} while (index >= 0);

				buffer.append(typeName, start, typeName.length());
				typeName = buffer.toString();
			}
		}

		return typeName;
	}

	private static String readString(ImageInputStream ddrStream, long ddrStringTableStart) {
		try {
			int stringOffset = ddrStream.readInt();
			if (stringOffset == -1) {
				return "";
			}

			long pos = ddrStream.getStreamPosition();
			long seekPos = ddrStringTableStart + stringOffset;
			ddrStream.seek(seekPos);
			int length = ddrStream.readUnsignedShort();
			if (length > 200) {
				throw new IOException("Improbable string length: " + length);
			}
			// TODO: Reuse buffer
			byte[] buffer = new byte[length];
			int read = ddrStream.read(buffer);

			if (read != length) {
				throw new IOException("StructureReader readString() Failed to read " + length + " at " + Long.toHexString(seekPos) + ". Result: " + read);
			}

			String result = new String(buffer, StandardCharsets.UTF_8);
			ddrStream.seek(pos);
			return result;
		} catch (IOException e) {
			// put the stack trace to the log
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			logger.logp(FINE, null, null, sw.toString());
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	public static class StructureDescriptor {
		String name;
		String superName;
		int sizeOf;
		List<FieldDescriptor> fields;
		List<ConstantDescriptor> constants;

		public StructureDescriptor() {
			super();
		}

		public StructureDescriptor(String line) {
			super();
			inflate(line);
		}

		@Override
		public String toString() {
			if (superName == null || superName.isEmpty()) {
				return String.valueOf(name);
			} else {
				return name + " extends " + superName;
			}
		}

		public String getPointerName() {
			return name + "Pointer";
		}

		public String getName() {
			return name;
		}

		public String getSuperName() {
			return superName != null ? superName : "";
		}

		public List<FieldDescriptor> getFields() {
			return fields;
		}

		public List<ConstantDescriptor> getConstants() {
			return constants;
		}

		public int getSizeOf() {
			return sizeOf;
		}

		private void inflate(String line) {
			String[] parts = line.split("\\|");
			if (parts.length < 3 || parts.length > 4) {
				throw new IllegalArgumentException("Superset file line is invalid: " + line);
			}
			constants = new ArrayList<>();
			fields = new ArrayList<>();
			name = parts[1];

			if (parts.length == 4) {
				superName = parts[3];
			} else {
				superName = "";
			}
		}

		public String deflate() {
			StringBuilder result = new StringBuilder();
			result.append("S|"); // 0
			result.append(getName()); // 1
			result.append("|");
			result.append(getPointerName()); // 2
			result.append("|");
			result.append(getSuperName()); // 3
			return result.toString();
		}

	}

	public static class ConstantDescriptor implements Comparable<ConstantDescriptor> {
		String name;
		long value; // U_64
		// TODO: what can hold a U_64 in Java

		public ConstantDescriptor(String name, long value) {
			super();
			this.name = name;
			this.value = value;
		}

		public ConstantDescriptor(String line) {
			super();
			inflate(line);
		}

		@Override
		public String toString() {
			return name + " = " + value;
		}

		public String getName() {
			return name;
		}

		public long getValue() {
			return value;
		}

		@Override
		public int compareTo(ConstantDescriptor o) {
			return getName().compareTo(o.getName());
		}

		private void inflate(String line) {
			String[] parts = line.split("\\|");
			if (parts.length != 2) {
				throw new IllegalArgumentException("Superset file line is invalid: " + line);
			}
			name = parts[1];
		}

		public String deflate() {
			return "C|" + getName(); //0 & 1
		}

		@Override
		public boolean equals(Object obj) {
			if ((obj == null) || !(obj instanceof ConstantDescriptor)) {
				return false;
			}
			ConstantDescriptor compareTo = (ConstantDescriptor) obj;
			return name.equals(compareTo.name) && (value == compareTo.value);
		}

		@Override
		public int hashCode() {
			return name.hashCode();
		}

	}

	public static class FieldDescriptor implements Comparable<FieldDescriptor> {
		String type;		  // Type as declared in Java
		String declaredType;  // Type as declared in C or C++
		String name;		  // Name as declared in Java
		String declaredName;  // Name as declared in C or C++
		int offset;
		boolean optional;
		boolean present;
		boolean required;

		public FieldDescriptor(int offset, String type, String declaredType, String name, String declaredName) {
			super();
			this.type = type;
			this.declaredType = declaredType;
			this.name = name;
			this.declaredName = declaredName;
			this.offset = offset;
			this.optional = false;
			this.present = true;
			this.required = false;
		}

		public void applyAliases(Map<String, String> aliasMap) {
			type = unalias(declaredType, aliasMap);
			cleanUpTypes();
		}

		/**
		 * Cleans up this type by mapping U_32 -> U32, removing any const declaration etc.
		 */
		public void cleanUpTypes() {
			type = stripUnderscore(type);
			type = stripTypeQualifiers(type);
			declaredType = stripUnderscore(declaredType);
		}

		private static final Pattern QualifierPattern = Pattern.compile("\\s*\\b(const|volatile)\\b\\s*");

		private static String stripTypeQualifiers(String type) {
			return filterOutPattern(type, QualifierPattern).trim();
		}

		private static final Pattern ScalarPattern = Pattern.compile("\\b([IU])_(?=\\d+|DATA\\b)");

		/*
		 * remove underscores to map to J9 types
		 * e.g. U_8 -> U8 or I_DATA -> IDATA
		 */
		private static String stripUnderscore(String type) {
			return ScalarPattern.matcher(type).replaceAll("$1");
		}

		/*
		 * Check the type name against the known type aliases.
		 * Probably want a better solution for this.
		 */
		private static String unalias(String type, Map<String, String> aliasMap) {
			CTypeParser parser = new CTypeParser(type);
			String result = parser.getCoreType();

			/* Unalias the type */
			if (aliasMap.containsKey(result)) {
				result = aliasMap.get(result);
			}

			return parser.getPrefix() + result + parser.getSuffix();
		}

		public String getName() {
			return name;
		}

		public String getDeclaredName() {
			return declaredName;
		}

		public String getType() {
			return type;
		}

		public String getDeclaredType() {
			return declaredType;
		}

		public int getOffset() {
			return offset;
		}

		public final boolean isOptional() {
			return optional;
		}

		public final boolean isPresent() {
			return present;
		}

		public final boolean isRequired() {
			return required;
		}

		@Override
		public String toString() {
			return type + " " + name + " Offset: " + offset;
		}

		@Override
		public int compareTo(FieldDescriptor o) {
			return getName().compareTo(o.getName());
		}

		public static Collection<FieldDescriptor> inflate(String line) {
			String[] parts = line.split("\\|");
			if (parts.length < 5 || ((parts.length - 3) % 2) != 0) {
				throw new IllegalArgumentException("Superset file line is invalid: " + line);
			}

			int count = (parts.length - 3) / 2;
			Collection<FieldDescriptor> result = new ArrayList<>(count);

			final String declaredName = parts[2];
			for (int i = 0; i < count; i++) {
				String fieldName = parts[1];
				if (i > 0) {
					fieldName = fieldName + "_v" + i;
				}

				FieldDescriptor fd = new FieldDescriptor(0, parts[3 + i * 2], parts[4 + i * 2], fieldName, declaredName);
				result.add(fd);
			}
			return result;
		}

		public String deflate() {
			StringBuilder result = new StringBuilder();
			result.append("F|"); // 0
			result.append(getName()); // 1
			result.append("|");
			result.append(getDeclaredName()); // 2
			result.append("|");
			result.append(StructureReader.simplifyType(getType())); // 3
			result.append("|");
			result.append(StructureReader.simplifyType(getDeclaredType())); // 4
			return result.toString();
		}

		@Override
		public boolean equals(Object obj) {
			if ((obj == null) || !(obj instanceof FieldDescriptor)) {
				return false;
			}
			FieldDescriptor compareTo = (FieldDescriptor) obj;
			return compareTo.deflate().equals(deflate());
		}

		@Override
		public int hashCode() {
			return name.hashCode();
		}

	}

	public byte[] getStructureClassBytes(String binaryName) throws ClassNotFoundException {
		/*
		 * Extract the simple class name (e.g. J9JavaClassFlags
		 * from com.ibm.j9ddr.vm29.structure.J9JavaClassFlags).
		 */
		String clazzName = binaryName.substring(binaryName.lastIndexOf('.') + 1);

		/* The structure name is the simple name of the requested class. */
		StructureDescriptor structure = structures.get(clazzName);

		if (structure == null) {
			throw new ClassNotFoundException(clazzName + " is not in core file");
		}

		String fullClassName = getPackageName(PackageNameType.STRUCTURE_PACKAGE_SLASH_NAME) + clazzName;

		return BytecodeGenerator.getStructureClassBytes(structure, fullClassName);
	}

	public byte[] getPointerClassBytes(String binaryName) throws ClassNotFoundException {
		/*
		 * Extract the simple class name (e.g. J9ClassPointer from
		 * com.ibm.j9ddr.vm29.pointer.generated.J9ClassPointer).
		 */
		String clazzName = binaryName.substring(binaryName.lastIndexOf('.') + 1);

		/*
		 * The structure name is derived by removing the 'Pointer' suffix.
		 * Names ending with 'Flags' are used directly (e.g. J9BuildFlags).
		 */
		String structureName;

		if (clazzName.endsWith("Pointer")) {
			structureName = clazzName.substring(0, clazzName.length() - 7);
		} else {
			structureName = clazzName;
		}

		StructureDescriptor structure = structures.get(structureName);

		if (structure == null) {
			throw new ClassNotFoundException(clazzName + " is not in core file");
		}

		String fullClassName = getPackageName(PackageNameType.POINTER_PACKAGE_SLASH_NAME) + clazzName;

		return BytecodeGenerator.getPointerClassBytes(this, typeManager, structure, fullClassName);
	}

	// TODO: Make this more efficient.  Probably change representation of fields and constants in Structure
	public long getConstantValue(String structureName, String constantName, long defaultValue) {
		if (constantName.equals("SIZEOF")) {
			return getStructureSizeOf(structureName);
		}

		for (ConstantDescriptor constant : getConstants(structureName)) {
			if (constant.getName().equals(constantName)) {
				return constant.getValue();
			}
		}

		return defaultValue;
	}

	public boolean getBuildFlagValue(String structureName, String constantName, boolean defaultValue) {
		long defaultLongValue = defaultValue ? 1 : 0;
		long value = getConstantValue(structureName, constantName, defaultLongValue);
		return value != 0;
	}

	public byte getSizeOfBool() {
		return header.getSizeofBool();
	}

	public byte getSizeOfUDATA() {
		return header.getSizeofUDATA();
	}

	public byte getBitFieldFormat() {
		return header.getBitfieldFormat();
	}

	public static String simplifyType(String type) {
		String working = type;

		/* Strip out the contents of array declarations */
		working = filterOutPattern(working, CONTENTS_OF_ARRAY_PATTERN);
		working = filterOutPattern(working, SPACES_BEFORE_SQUARE_BRACKETS_PATTERN);
		working = filterOutPattern(working, SPACES_AFTER_SQUARE_BRACKETS_PATTERN);
		working = filterOutPattern(working, SPACES_BEFORE_ASTERISKS_PATTERN);
		working = filterOutPattern(working, SPACES_AFTER_ASTERISKS_PATTERN);

		return working;
	}

	static String filterOutPattern(String input, Pattern pattern) {
		return pattern.matcher(input).replaceAll("");
	}

}
