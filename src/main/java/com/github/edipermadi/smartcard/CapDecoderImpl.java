package com.github.edipermadi.smartcard;

import com.github.edipermadi.smartcard.exc.*;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CAP File decoder implementation
 *
 * @author Edi Permadi
 */
public class CapDecoderImpl extends CapDecoderImplBase implements CapDecoder {

    @Override
    public Cap decode(final InputStream stream) throws CapException {
        final ZipInputStream zis = new ZipInputStream(stream);
        try {
            final CapBuilder builder = new CapBuilder();
            while (true) {
                final ZipEntry ze = zis.getNextEntry();
                if (ze == null) {
                    break;
                } else if (ze.isDirectory()) {
                    continue;
                }

                final String path = ze.getName();
                final String name = Paths.get(path).toFile().getName();
                final byte[] payload = IOUtils.toByteArray(zis);
                zis.closeEntry();

                switch (name) {
                    case COMPONENT_Header:
                        builder.setHeader(decodeCapHeader(payload));
                        break;
                    case COMPONENT_Directory:
                        builder.setDirectory(decodeCapDirectory(payload));
                        break;
                    case COMPONENT_Applet:
                        builder.setApplet(decodeCapApplet(payload));
                        break;
                    case COMPONENT_Import:
                    case COMPONENT_ConstantPool:
                    case COMPONENT_Class:
                    case COMPONENT_Method:
                    case COMPONENT_StaticField:
                    case COMPONENT_ReferenceLocation:
                    case COMPONENT_Export:
                    case COMPONENT_Descriptor:
                    case COMPONENT_Debug:
                }
            }

            return builder.build();
        } catch (final IOException ex) {
            throw new CapFormatException("unrecogzied CAP format", ex);
        } finally {
            IOUtils.closeQuietly(zis);
        }
    }

    /**
     * Decode CAP header. The following is the structure of CAP header
     * <pre>
     * header_component {
     *     u1 tag
     *     u2 size
     *     u4 magic
     *     u1 minor_version
     *     u1 major_version
     *     ui flags
     *     package_info package
     *     package_name_info package_name
     * }
     *
     * package_info {
     *     u1 minor_version
     *     u1 major_version
     *     u1 AID_length
     *     u1 AID[AID_length]
     * }
     *
     * package_name_info {
     *     u1 name_length
     *     u1 name[name_length]
     * }
     * </pre>
     *
     * @param payload CAP header payload
     * @return CAP Header object
     * @throws CapDecodeException when CAP Header decoding failed
     */
    private Cap.Header decodeCapHeader(final byte[] payload) throws CapDecodeException {
        if (ArrayUtils.isEmpty(payload)) {
            throw new IllegalArgumentException("header payload is null");
        }

        final CapHeaderBuilder builder = new CapHeaderBuilder();
        final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        final DataInputStream dis = new DataInputStream(bais);
        try {
            /* parse tag */
            final int componentTag = dis.readByte();
            if (componentTag != TAG_COMPONENT_Header) {
                throw CapDecodeHeaderException.invalidTag(componentTag);
            }

            /* parse size */
            final int componentSize = dis.readShort();
            if ((componentSize < 0) || (dis.available() < componentSize)) {
                throw CapDecodeHeaderException.invalidSize();
            }

            /* parse magic */
            final int componentMagicCode = dis.readInt();
            if (componentMagicCode != 0xdecaffed) {
                throw CapDecodeHeaderException.invalidMagic();
            }

            /* parse version and flags */
            builder.setHeaderVersion(parseVersion(dis))
                    .setHeaderFlags(dis.readByte());

            /* parse package info */
            final int packageInfoVersion = parseVersion(dis);
            final int aidLength = dis.readByte();
            if ((aidLength < 5) || (aidLength > 16)) {
                throw CapDecodeHeaderException.invalidAidLength();
            }

            /* parse AID payload */
            final byte[] aid = new byte[aidLength];
            if (dis.read(aid) != aidLength) {
                throw CapDecodeHeaderException.invalidPackageAID();
            }
            builder.setPackageInfo(packageInfoVersion, Hex.encodeHexString(aid));

            /* optionally set package name info */
            if (bais.available() > 0) {
                final int nameLength = dis.readByte();
                if (nameLength < 0) {
                    throw CapDecodeHeaderException.invalidPackageNameLength();
                }

                /* parse package name */
                if (nameLength > 0) {
                    final byte[] name = new byte[nameLength];
                    if (dis.read(name) != nameLength) {
                        throw CapDecodeHeaderException.invalidPackageName();
                    }

                    builder.setPackageName(new String(name, StandardCharsets.UTF_8));
                }
            }

            return builder.build();
        } catch (final IOException ex) {
            throw new CapDecodeException("failed to parse CAP header", ex);
        } finally {
            IOUtils.closeQuietly(dis);
            IOUtils.closeQuietly(bais);
        }
    }

    /**
     * Decode CAP directory component. The following is the structure of directory component
     * <pre>
     * directory_component {
     *     u1 tag
     *     u2 size
     *     u2 component_sizes[12]
     *     static_field_size_info static_field_size
     *     u1 import_count
     *     u1 applet_count
     *     u1 custom_count
     *     custom_component_info custom_components[custom_count]
     * }
     *
     * static_field_size_info {
     *     u2 image_size
     *     u2 array_init_count
     *     u2 array_init_size
     * }
     *
     * custom_component_info {
     *     u1 component_tag
     *     u2 size
     *     u1 AID_length
     *     u1 AID[AID_length]
     * }
     * </pre>
     *
     * @param payload CAP directory component payload
     * @return CAP directory component
     * @throws CapDecodeException when decoding failed
     */
    private Cap.Directory decodeCapDirectory(final byte[] payload) throws CapDecodeException {
        if (ArrayUtils.isEmpty(payload)) {
            throw new IllegalArgumentException("directory payload is null");
        }

        final CapDirectoryBuilder builder = new CapDirectoryBuilder();
        final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        final DataInputStream dis = new DataInputStream(bais);
        try {
            /* parse tag */
            final int componentTag = dis.readByte();
            if (componentTag != TAG_COMPONENT_Directory) {
                throw CapDecodeDirectoryException.invalidTag(componentTag);
            }

            /* parse size */
            final int componentSize = dis.readShort();
            if ((componentSize < 0) || (dis.available() < componentSize)) {
                throw CapDecodeDirectoryException.invalidSize();
            }

            /* parse component sizes */
            for (int i = 0; i < 11; i++) {
                final int v = dis.readShort();
                builder.addComponentSize(v);
            }

            /* parse static_field_size_info */
            final int imageSize = dis.readShort();
            final int arrayInitCount = dis.readShort();
            final int arrayInitSize = dis.readShort();

            /* set static_field_size_info, import_count and applet_count */
            builder.setStaticFieldSize(imageSize, arrayInitCount, arrayInitSize)
                    .setImportCount(dis.readByte())
                    .setAppletCount(dis.readByte());

            /* parse array of custom component info */
            final int customCount = dis.readByte();
            if ((customCount < 0) || (customCount > 127)) {
                throw CapDecodeDirectoryException.invalidComponentTag();
            }

            for (int i = 0; i < customCount; i++) {
                /* decode component tag */
                final int customComponentTag = dis.readByte();
                if (customComponentTag < 128) {
                    throw CapDecodeDirectoryException.invalidComponentTag();
                }

                /* decode component size */
                final int customComponentSize = dis.readShort();
                if (dis.available() < customComponentSize) {
                    throw CapDecodeDirectoryException.truncatedComponent();
                }

                /* decode component AID length */
                final int customComponentAidLength = dis.readByte();
                if ((customComponentAidLength < 5) || (customComponentAidLength > 16)) {
                    throw CapDecodeDirectoryException.invalidCustomComponentAIDLength();
                }

                /* decode component AID payload */
                final byte[] customComponentAid = new byte[customComponentAidLength];
                if (dis.read(customComponentAid) != customComponentAidLength) {
                    throw CapDecodeDirectoryException.truncatedComponent();
                }

                builder.addCustomComponent(componentTag, Hex.encodeHexString(customComponentAid));
            }

            return builder.build();
        } catch (final IOException ex) {
            throw new CapDecodeException("failed to parse CAP directory", ex);
        } finally {
            IOUtils.closeQuietly(dis);
            IOUtils.closeQuietly(bais);
        }
    }

    /**
     * Decode CAP Applet
     *
     * @param payload CAP applet component payload
     * @return CAP applet component object
     * @throws CapDecodeException hwn decoding failed
     */
    private Cap.Applet decodeCapApplet(final byte[] payload) throws CapDecodeException {
        if (ArrayUtils.isEmpty(payload)) {
            throw new IllegalArgumentException("applet payload is null");
        }

        final CapAppletBuilder builder = new CapAppletBuilder();
        final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        final DataInputStream dis = new DataInputStream(bais);
        try {
            /* parse tag */
            final int componentTag = dis.readByte();
            if (componentTag != TAG_COMPONENT_Applet) {
                throw CapDecodeAppletException.invalidTag(componentTag);
            }

            /* parse size */
            final int componentSize = dis.readShort();
            if ((componentSize < 0) || (dis.available() < componentSize)) {
                throw CapDecodeAppletException.invalidSize();
            }

            /* parse count of applet */
            final int count = dis.readByte();
            if (count < 1) {
                throw CapDecodeAppletException.invalidAppletCount();
            }

            /* parse applet entries */
            for (int i = 0; i < count; i++) {
                /* parse AID length */
                int aidLength = dis.readByte();
                if ((aidLength < 5) || (aidLength > 16)) {
                    throw CapDecodeAppletException.invalidAIDLength();
                }

                /* parse AID */
                final byte[] aid = new byte[aidLength];
                if (dis.read(aid) != aidLength) {
                    throw CapDecodeAppletException.invalidAID();
                }

                /* parse install method offset */
                final int installMethodOffset = dis.readShort();
                if (installMethodOffset < 0) {
                    throw CapDecodeAppletException.invalidInstallMethodOffset();
                }

                builder.addApplet(Hex.encodeHexString(aid), installMethodOffset);
            }

            return builder.build();
        } catch (final IOException ex) {
            throw new CapDecodeException("failed to parse CAP applet", ex);
        } finally {
            IOUtils.closeQuietly(dis);
            IOUtils.closeQuietly(bais);
        }
    }

    private int parseVersion(final DataInputStream dis) throws IOException {
        int version;
        version = dis.readByte() & 0xff;
        version += (dis.readByte() & 0xff) << 8;
        return version;
    }
}
