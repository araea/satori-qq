import * as $protobuf from "@ohos/protobufjs";
import Long = require("long");
/** Properties of a MetaData. */
export interface IMetaData {

    /** MetaData key */
    key?: (string|null);

    /** MetaData value */
    value?: (string|null);
}

/** Represents a MetaData. */
export class MetaData implements IMetaData {

    /**
     * Constructs a new MetaData.
     * @param [properties] Properties to set
     */
    constructor(properties?: IMetaData);

    /** MetaData key. */
    public key: string;

    /** MetaData value. */
    public value: string;

    /**
     * Creates a new MetaData instance using the specified properties.
     * @param [properties] Properties to set
     * @returns MetaData instance
     */
    public static create(properties?: IMetaData): MetaData;

    /**
     * Encodes the specified MetaData message. Does not implicitly {@link MetaData.verify|verify} messages.
     * @param message MetaData message or plain object to encode
     * @param [writer] Writer to encode to
     * @returns Writer
     */
    public static encode(message: IMetaData, writer?: $protobuf.Writer): $protobuf.Writer;

    /**
     * Encodes the specified MetaData message, length delimited. Does not implicitly {@link MetaData.verify|verify} messages.
     * @param message MetaData message or plain object to encode
     * @param [writer] Writer to encode to
     * @returns Writer
     */
    public static encodeDelimited(message: IMetaData, writer?: $protobuf.Writer): $protobuf.Writer;

    /**
     * Decodes a MetaData message from the specified reader or buffer.
     * @param reader Reader or buffer to decode from
     * @param [length] Message length if known beforehand
     * @returns MetaData
     * @throws {Error} If the payload is not a reader or valid buffer
     * @throws {$protobuf.util.ProtocolError} If required fields are missing
     */
    public static decode(reader: ($protobuf.Reader|Uint8Array), length?: number): MetaData;

    /**
     * Decodes a MetaData message from the specified reader or buffer, length delimited.
     * @param reader Reader or buffer to decode from
     * @returns MetaData
     * @throws {Error} If the payload is not a reader or valid buffer
     * @throws {$protobuf.util.ProtocolError} If required fields are missing
     */
    public static decodeDelimited(reader: ($protobuf.Reader|Uint8Array)): MetaData;

    /**
     * Verifies a MetaData message.
     * @param message Plain object to verify
     * @returns `null` if valid, otherwise the reason why it is not
     */
    public static verify(message: { [k: string]: any }): (string|null);

    /**
     * Creates a MetaData message from a plain object. Also converts values to their respective internal types.
     * @param object Plain object
     * @returns MetaData
     */
    public static fromObject(object: { [k: string]: any }): MetaData;

    /**
     * Creates a plain object from a MetaData message. Also converts values to other types if specified.
     * @param message MetaData
     * @param [options] Conversion options
     * @returns Plain object
     */
    public static toObject(message: MetaData, options?: $protobuf.IConversionOptions): { [k: string]: any };

    /**
     * Converts this MetaData to JSON.
     * @returns JSON object
     */
    public toJSON(): { [k: string]: any };

    /**
     * Gets the default type url for MetaData
     * @param [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
     * @returns The default type url
     */
    public static getTypeUrl(typeUrlPrefix?: string): string;
}

/** Properties of a OIDBSSOPkg. */
export interface IOIDBSSOPkg {

    /** OIDBSSOPkg uint32Command */
    uint32Command?: (number|null);

    /** OIDBSSOPkg uint32ServiceType */
    uint32ServiceType?: (number|null);

    /** OIDBSSOPkg uint32Result */
    uint32Result?: (number|null);

    /** OIDBSSOPkg bytesBodybuffer */
    bytesBodybuffer?: (Uint8Array|null);

    /** OIDBSSOPkg strErrorMsg */
    strErrorMsg?: (string|null);

    /** OIDBSSOPkg strClientVersion */
    strClientVersion?: (string|null);

    /** OIDBSSOPkg trpcTransInfo */
    trpcTransInfo?: (IMetaData[]|null);
}

/** Represents a OIDBSSOPkg. */
export class OIDBSSOPkg implements IOIDBSSOPkg {

    /**
     * Constructs a new OIDBSSOPkg.
     * @param [properties] Properties to set
     */
    constructor(properties?: IOIDBSSOPkg);

    /** OIDBSSOPkg uint32Command. */
    public uint32Command: number;//0x102a

    /** OIDBSSOPkg uint32ServiceType. */
    public uint32ServiceType: number;//0

    /** OIDBSSOPkg uint32Result. */
    public uint32Result: number;//0

    /** OIDBSSOPkg bytesBodybuffer. */
    public bytesBodybuffer: Uint8Array;//data

    /** OIDBSSOPkg strErrorMsg. */
    public strErrorMsg: string;

    /** OIDBSSOPkg strClientVersion. */
    public strClientVersion: string;

    /** OIDBSSOPkg trpcTransInfo. */
    public trpcTransInfo: IMetaData[];

    /**
     * Creates a new OIDBSSOPkg instance using the specified properties.
     * @param [properties] Properties to set
     * @returns OIDBSSOPkg instance
     */
    public static create(properties?: IOIDBSSOPkg): OIDBSSOPkg;

    /**
     * Encodes the specified OIDBSSOPkg message. Does not implicitly {@link OIDBSSOPkg.verify|verify} messages.
     * @param message OIDBSSOPkg message or plain object to encode
     * @param [writer] Writer to encode to
     * @returns Writer
     */
    public static encode(message: IOIDBSSOPkg, writer?: $protobuf.Writer): $protobuf.Writer;

    /**
     * Encodes the specified OIDBSSOPkg message, length delimited. Does not implicitly {@link OIDBSSOPkg.verify|verify} messages.
     * @param message OIDBSSOPkg message or plain object to encode
     * @param [writer] Writer to encode to
     * @returns Writer
     */
    public static encodeDelimited(message: IOIDBSSOPkg, writer?: $protobuf.Writer): $protobuf.Writer;

    /**
     * Decodes a OIDBSSOPkg message from the specified reader or buffer.
     * @param reader Reader or buffer to decode from
     * @param [length] Message length if known beforehand
     * @returns OIDBSSOPkg
     * @throws {Error} If the payload is not a reader or valid buffer
     * @throws {$protobuf.util.ProtocolError} If required fields are missing
     */
    public static decode(reader: ($protobuf.Reader|Uint8Array), length?: number): OIDBSSOPkg;

    /**
     * Decodes a OIDBSSOPkg message from the specified reader or buffer, length delimited.
     * @param reader Reader or buffer to decode from
     * @returns OIDBSSOPkg
     * @throws {Error} If the payload is not a reader or valid buffer
     * @throws {$protobuf.util.ProtocolError} If required fields are missing
     */
    public static decodeDelimited(reader: ($protobuf.Reader|Uint8Array)): OIDBSSOPkg;

    /**
     * Verifies a OIDBSSOPkg message.
     * @param message Plain object to verify
     * @returns `null` if valid, otherwise the reason why it is not
     */
    public static verify(message: { [k: string]: any }): (string|null);

    /**
     * Creates a OIDBSSOPkg message from a plain object. Also converts values to their respective internal types.
     * @param object Plain object
     * @returns OIDBSSOPkg
     */
    public static fromObject(object: { [k: string]: any }): OIDBSSOPkg;

    /**
     * Creates a plain object from a OIDBSSOPkg message. Also converts values to other types if specified.
     * @param message OIDBSSOPkg
     * @param [options] Conversion options
     * @returns Plain object
     */
    public static toObject(message: OIDBSSOPkg, options?: $protobuf.IConversionOptions): { [k: string]: any };

    /**
     * Converts this OIDBSSOPkg to JSON.
     * @returns JSON object
     */
    public toJSON(): { [k: string]: any };

    /**
     * Gets the default type url for OIDBSSOPkg
     * @param [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
     * @returns The default type url
     */
    public static getTypeUrl(typeUrlPrefix?: string): string;
}
